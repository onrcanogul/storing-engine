package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.format.FileHeader;
import com.onurcanogul.bitcask.format.FormatLimits;
import com.onurcanogul.bitcask.format.LogRecord;
import com.onurcanogul.bitcask.format.RecordCodec;
import com.onurcanogul.bitcask.format.RecordType;
import com.onurcanogul.bitcask.index.KeyDirEntry;
import com.onurcanogul.bitcask.recovery.Recovery;
import com.onurcanogul.bitcask.recovery.RecoveryReport;
import com.onurcanogul.bitcask.recovery.RecoveryResult;
import com.onurcanogul.bitcask.store.DirectoryLock;
import com.onurcanogul.bitcask.store.OpenFileLimit;
import com.onurcanogul.bitcask.store.SegmentFiles;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An append-only key-value store with an in-memory index.
 *
 * <p>Writes are appended to the active segment; when it reaches
 * {@link BitcaskConfig#maxSegmentSize()} a new one is started and the previous
 * becomes immutable. The index maps each live key to the segment and offset of
 * its most recent record, so a read is always exactly one positional read
 * whatever the size of the data. The price is that every key must fit in memory.
 *
 * <p>One writer, many readers: writes are serialized on this instance while
 * reads take no lock. One process per directory, enforced by
 * {@link DirectoryLock}.
 */
public final class Bitcask implements AutoCloseable {

    private final Path directory;
    private final BitcaskConfig config;
    private final DirectoryLock lock;
    private final Map<ByteBuffer, KeyDirEntry> keyDir;
    private final RecoveryReport report;

    /** Every segment stays open: the index points into all of them. */
    private final Map<Integer, FileChannel> channels;

    private int activeFileId;
    private FileChannel activeChannel;
    private long writePos;
    private long nextSeq;

    private volatile boolean closed;

    private Bitcask(Path directory,
                    BitcaskConfig config,
                    DirectoryLock lock,
                    Map<Integer, FileChannel> channels,
                    Map<ByteBuffer, KeyDirEntry> keyDir,
                    RecoveryReport report,
                    int activeFileId,
                    long writePos,
                    long nextSeq) {
        this.directory = directory;
        this.config = config;
        this.lock = lock;
        this.channels = channels;
        this.keyDir = keyDir;
        this.report = report;
        this.activeFileId = activeFileId;
        this.activeChannel = channels.get(activeFileId);
        this.writePos = writePos;
        this.nextSeq = nextSeq;
    }

    /**
     * Opens the store in {@code dir}, creating the directory if necessary.
     *
     * @throws IOException if the directory is already open, or a segment is not
     *                     one of ours, or the log is damaged and the configured
     *                     {@link com.onurcanogul.bitcask.recovery.RecoveryMode}
     *                     refuses to continue
     */
    public static Bitcask open(Path dir, BitcaskConfig config) throws IOException {
        Files.createDirectories(dir);
        DirectoryLock lock = DirectoryLock.acquire(dir);

        Map<Integer, FileChannel> channels = new HashMap<>();
        try {
            SegmentFiles.adoptLegacyLog(dir);

            List<Integer> fileIds = new ArrayList<>(SegmentFiles.listFileIds(dir));
            if (fileIds.isEmpty()) {
                fileIds.add(SegmentFiles.FIRST_FILE_ID);
            }

            for (int fileId : fileIds) {
                try {
                    channels.put(fileId, openSegment(dir, fileId));
                } catch (IOException e) {
                    throw OpenFileLimit.describe(e, channels.size());
                }
            }

            Map<ByteBuffer, KeyDirEntry> keyDir = new ConcurrentHashMap<>();
            RecoveryResult recovered = Recovery.replay(channels, fileIds, keyDir, config.recoveryMode());

            return new Bitcask(dir, config, lock, channels, keyDir, recovered.report(),
                    recovered.activeFileId(), recovered.writePos(), recovered.maxSeq() + 1);

        } catch (IOException | RuntimeException e) {
            // A half-finished open must not leave the directory locked forever.
            channels.values().forEach(Bitcask::closeQuietly);
            releaseQuietly(lock, e);
            throw e;
        }
    }

    /**
     * Opens one segment, writing its header if the file is new.
     *
     * <p>A file shorter than a header cannot hold a record, so it is repaired
     * rather than rejected: that is exactly what a rotation interrupted between
     * creating the file and writing its header leaves behind.
     */
    private static FileChannel openSegment(Path dir, int fileId) throws IOException {
        Path path = SegmentFiles.pathOf(dir, fileId);

        FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            if (channel.size() < FileHeader.SIZE) {
                channel.truncate(0);
                FileHeader.write(channel);
            } else {
                FileHeader.validate(channel);
            }
            return channel;
        } catch (IOException | RuntimeException e) {
            closeQuietly(channel);
            throw e;
        }
    }

    /**
     * Stores {@code value} under {@code key}, replacing any previous value.
     *
     * <p>Serialized: this is the single writer. Readers never take this lock.
     *
     * <p>The key is copied; the value is not, since it is written to disk and
     * then released. Mutating either array after this call returns is safe.
     *
     * @throws IllegalArgumentException if the key is empty or either side is over its limit
     */
    public synchronized void put(byte[] key, byte[] value) throws IOException {
        ensureOpen();
        validateKey(key);

        byte[] storedValue = (value == null) ? new byte[0] : value;
        if (storedValue.length > config.maxValueSize()) {
            throw new IllegalArgumentException(
                    "value too large: " + storedValue.length + " > " + config.maxValueSize());
        }

        append(RecordType.PUT, key, storedValue,
                (position, size, seq) -> keyDir.put(indexKeyCopyOf(key),
                        new KeyDirEntry(activeFileId, position, size, seq)));
    }

    /**
     * Removes {@code key}, if it is present.
     *
     * <p>Deletion is a write. In an append-only log nothing can be erased, so a
     * tombstone record is appended to cancel the earlier PUT during replay.
     * Dropping the index entry alone would not survive a restart: the replay
     * would find the old PUT and resurrect the deleted value.
     *
     * @return true if the key was present and is now gone
     */
    public synchronized boolean delete(byte[] key) throws IOException {
        ensureOpen();
        validateKey(key);

        ByteBuffer lookupKey = ByteBuffer.wrap(key);
        if (!keyDir.containsKey(lookupKey)) {
            // Nothing to cancel, so a tombstone here would be pure garbage.
            return false;
        }

        append(RecordType.TOMBSTONE, key, new byte[0],
                (position, size, seq) -> keyDir.remove(lookupKey));
        return true;
    }

    /** What the index update should be, once the record is safely on its way to disk. */
    @FunctionalInterface
    private interface IndexUpdate {
        void apply(long position, int size, long seq);
    }

    private void append(RecordType type, byte[] key, byte[] value, IndexUpdate indexUpdate)
            throws IOException {
        int size = RecordCodec.recordSize(key.length, value.length);

        // Records are never split across segments, so one that cannot fit in an
        // empty segment fits nowhere. Rotating would just produce a fresh segment
        // it still does not fit in, and then another.
        if (FileHeader.SIZE + size > config.maxSegmentSize()) {
            throw new IllegalArgumentException(
                    "record of " + size + " bytes cannot fit in any segment of "
                            + config.maxSegmentSize() + " bytes"
                            + " (key " + key.length + " B, value " + value.length + " B)");
        }
        if (writePos + size > config.maxSegmentSize()) {
            rotate();
        }

        long seq = nextSeq;
        ByteBuffer record = RecordCodec.encode(seq, System.currentTimeMillis(), type, key, value);
        long position = writePos;

        writeFully(record, position);
        if (config.syncPolicy() == SyncPolicy.ALWAYS) {
            activeChannel.force(false);
        }

        // Disk before memory. In the other order a failed write would leave the
        // index pointing at a record that does not exist.
        indexUpdate.apply(position, size, seq);

        // Advanced last, so a failed write leaves no gap in the log.
        writePos = position + size;
        nextSeq = seq + 1;
    }

    /**
     * Closes the active segment to further writes and starts the next one.
     *
     * <p>The outgoing segment is fsynced regardless of {@link SyncPolicy}. It
     * happens once per segment, so the cost is amortised to nothing, and it buys
     * something the recovery logic depends on: a closed segment is known to have
     * reached the disk, which is why damage found in one is treated as real
     * corruption rather than as a torn tail.
     *
     * <p>The old channel stays open — the index still points into it.
     */
    private void rotate() throws IOException {
        activeChannel.force(false);

        int newFileId = activeFileId + 1;
        // CREATE_NEW rather than CREATE: if that file somehow exists, something
        // is badly wrong and overwriting it would destroy records.
        FileChannel newChannel;
        try {
            newChannel = FileChannel.open(SegmentFiles.pathOf(directory, newFileId),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
        } catch (IOException e) {
            // A long-running store runs out of descriptors here rather than at open.
            throw OpenFileLimit.describe(e, channels.size());
        }
        FileHeader.write(newChannel);

        channels.put(newFileId, newChannel);
        activeFileId = newFileId;
        activeChannel = newChannel;
        writePos = FileHeader.SIZE;
    }

    /**
     * Returns the value stored under {@code key}, or {@code null} if there is none.
     *
     * <p>Takes no lock: the index lookup is lock-free and the disk read is
     * positional, so any number of threads may read at once.
     *
     * <p>The returned array is freshly allocated and referenced nowhere inside the
     * engine, so the caller may do as it likes with it.
     *
     * @throws IOException if the stored record fails verification
     */
    public byte[] get(byte[] key) throws IOException {
        ensureOpen();
        validateKey(key);

        KeyDirEntry entry = keyDir.get(ByteBuffer.wrap(key));
        if (entry == null) {
            return null;
        }

        FileChannel channel = channels.get(entry.fileId());
        if (channel == null) {
            throw new IOException("index points at segment " + entry.fileId()
                    + ", which is not open: internal inconsistency");
        }

        ByteBuffer buf = ByteBuffer.allocate(entry.recordSize());
        readFully(channel, buf, entry.recordPos());
        buf.flip();

        LogRecord record = RecordCodec.decode(buf);

        // The checksum proves the record is intact; it cannot prove it is the
        // right one. A wrong offset lands on a perfectly valid record belonging
        // to some other key, and only this comparison catches that.
        if (!Arrays.equals(record.key(), key)) {
            throw new IOException("index and log disagree in segment " + entry.fileId()
                    + " at offset " + entry.recordPos() + ": the stored key is not the requested key");
        }
        if (record.type() != RecordType.PUT) {
            throw new IOException("tombstone reachable through the index in segment "
                    + entry.fileId() + " at offset " + entry.recordPos() + ": internal inconsistency");
        }

        return record.value();
    }

    /** What the last recovery found. Never null. */
    public RecoveryReport recoveryReport() {
        return report;
    }

    /** Number of live keys. */
    public int size() {
        ensureOpen();
        return keyDir.size();
    }

    /** Number of segment files currently open. */
    public int segmentCount() {
        ensureOpen();
        return channels.size();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            for (FileChannel channel : channels.values()) {
                channel.close();
            }
        } finally {
            lock.release();
        }
    }

    private void readFully(FileChannel channel, ByteBuffer buf, long position) throws IOException {
        long at = position;
        while (buf.hasRemaining()) {
            int read = channel.read(buf, at);
            if (read < 0) {
                throw new IOException("segment ended early at offset " + at
                        + ", " + buf.remaining() + " bytes short");
            }
            at += read;
        }
    }

    private void writeFully(ByteBuffer buf, long position) throws IOException {
        long at = position;
        while (buf.hasRemaining()) {
            at += activeChannel.write(buf, at);
        }
    }

    /**
     * Copies the key before wrapping it as an index key.
     *
     * <p>Without the copy, a caller reusing its buffer would mutate the array the
     * map is keyed on. The entry would then be unfindable under either the old or
     * the new key — alive in memory, unreachable in practice — while the record on
     * disk stayed perfectly correct.
     */
    private static ByteBuffer indexKeyCopyOf(byte[] key) {
        return ByteBuffer.wrap(Arrays.copyOf(key, key.length));
    }

    private static void validateKey(byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("key must not be empty");
        }
        if (key.length > FormatLimits.MAX_KEY_SIZE) {
            throw new IllegalArgumentException(
                    "key too long: " + key.length + " > " + FormatLimits.MAX_KEY_SIZE);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("engine is closed");
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Already unwinding a failed open.
        }
    }

    /**
     * Releases the lock while an open is failing.
     *
     * <p>A failure here is attached to the original error rather than replacing
     * it: "could not release the lock" would otherwise hide "this file is not a
     * bitcask log", which is the fact the caller actually needs.
     */
    private static void releaseQuietly(DirectoryLock lock, Throwable primary) {
        try {
            lock.release();
        } catch (IOException suppressed) {
            primary.addSuppressed(suppressed);
        }
    }
}
