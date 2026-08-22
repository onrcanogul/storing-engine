package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.recovery.RecoveryReport;
import com.onurcanogul.bitcask.recovery.RecoveryResult;
import com.onurcanogul.bitcask.recovery.Recovery;
import com.onurcanogul.bitcask.recovery.RecoveryMode;
import com.onurcanogul.bitcask.format.FileHeader;
import com.onurcanogul.bitcask.format.FormatLimits;
import com.onurcanogul.bitcask.format.LogRecord;
import com.onurcanogul.bitcask.format.RecordCodec;
import com.onurcanogul.bitcask.format.RecordType;
import com.onurcanogul.bitcask.index.KeyDirEntry;
import com.onurcanogul.bitcask.store.DirectoryLock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An append-only key-value store with an in-memory index.
 *
 * <p>Every write is appended to a single log file, and the index maps each live
 * key to the offset of its most recent record. A read is therefore always
 * exactly one positional read, whatever the size of the data. The price is that
 * every key must fit in memory.
 *
 * <p>One writer, many readers: writes are serialized on this instance while
 * reads take no lock. One process per directory, enforced by
 * {@link DirectoryLock}.
 */
public final class Bitcask implements AutoCloseable {

    public static final String DATA_FILE_NAME = "data.log";

    private final BitcaskConfig config;
    private final DirectoryLock lock;
    private final FileChannel channel;
    private final Map<ByteBuffer, KeyDirEntry> keyDir;
    private final RecoveryReport report;

    private long writePos;
    private long nextSeq;

    private volatile boolean closed;

    private Bitcask(BitcaskConfig config,
                    DirectoryLock lock,
                    FileChannel channel,
                    Map<ByteBuffer, KeyDirEntry> keyDir,
                    RecoveryReport report,
                    long writePos,
                    long nextSeq) {
        this.config = config;
        this.lock = lock;
        this.channel = channel;
        this.keyDir = keyDir;
        this.report = report;
        this.writePos = writePos;
        this.nextSeq = nextSeq;
    }

    /**
     * Opens the store in {@code dir}, creating the directory if necessary.
     *
     * @throws IOException if the directory is already open, or the log is not one
     *                     of ours, or it is damaged and the configured
     *                     {@link RecoveryMode} refuses to continue
     */
    public static Bitcask open(Path dir, BitcaskConfig config) throws IOException {
        Files.createDirectories(dir);
        DirectoryLock lock = DirectoryLock.acquire(dir);

        FileChannel channel = null;
        try {
            Path logFile = dir.resolve(DATA_FILE_NAME);
            boolean fresh = !Files.exists(logFile) || Files.size(logFile) == 0;

            channel = FileChannel.open(logFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);

            if (fresh) {
                FileHeader.write(channel);
            } else {
                FileHeader.validate(channel);
            }

            Map<ByteBuffer, KeyDirEntry> keyDir = new ConcurrentHashMap<>();
            RecoveryResult recovered = Recovery.replay(channel, keyDir, config.recoveryMode());

            return new Bitcask(config, lock, channel, keyDir,
                    recovered.report(), recovered.endOffset(), recovered.maxSeq() + 1);

        } catch (IOException | RuntimeException e) {
            // A half-finished open must not leave the directory locked forever.
            closeQuietly(channel);
            releaseQuietly(lock, e);
            throw e;
        }
    }

    /** What the last recovery found. Never null. */
    public RecoveryReport recoveryReport() {
        return report;
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

        long seq = nextSeq;
        ByteBuffer record = RecordCodec.encode(
                seq, System.currentTimeMillis(), RecordType.PUT, key, storedValue);
        int size = record.remaining();
        long position = writePos;

        writeFully(record, position);
        if (config.syncPolicy() == SyncPolicy.ALWAYS) {
            channel.force(false);
        }

        // Disk before memory. In the other order a failed write would leave the
        // index pointing at a record that does not exist.
        keyDir.put(indexKeyCopyOf(key), new KeyDirEntry(0, position, size, seq));

        // Advanced last, so a failed write leaves no gap in the log.
        writePos = position + size;
        nextSeq = seq + 1;
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

        long seq = nextSeq;
        ByteBuffer record = RecordCodec.encode(
                seq, System.currentTimeMillis(), RecordType.TOMBSTONE, key, new byte[0]);
        int size = record.remaining();
        long position = writePos;

        writeFully(record, position);
        if (config.syncPolicy() == SyncPolicy.ALWAYS) {
            channel.force(false);
        }

        // Disk before memory, exactly as in put.
        keyDir.remove(lookupKey);

        writePos = position + size;
        nextSeq = seq + 1;
        return true;
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

        ByteBuffer buf = ByteBuffer.allocate(entry.recordSize());
        readFully(buf, entry.recordPos());
        buf.flip();

        LogRecord record = RecordCodec.decode(buf);

        // The checksum proves the record is intact; it cannot prove it is the
        // right one. A wrong offset lands on a perfectly valid record belonging
        // to some other key, and only this comparison catches that.
        if (!Arrays.equals(record.key(), key)) {
            throw new IOException("index and log disagree at offset " + entry.recordPos()
                    + ": the stored key is not the requested key");
        }
        if (record.type() != RecordType.PUT) {
            throw new IOException("tombstone reachable through the index at offset "
                    + entry.recordPos() + ": internal inconsistency");
        }

        return record.value();
    }

    /** Number of live keys. */
    public int size() {
        ensureOpen();
        return keyDir.size();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            channel.close();
        } finally {
            lock.release();
        }
    }

    private void readFully(ByteBuffer buf, long position) throws IOException {
        long at = position;
        while (buf.hasRemaining()) {
            int read = channel.read(buf, at);
            if (read < 0) {
                throw new IOException("log ended early at offset " + at
                        + ", " + buf.remaining() + " bytes short");
            }
            at += read;
        }
    }

    private void writeFully(ByteBuffer buf, long position) throws IOException {
        long at = position;
        while (buf.hasRemaining()) {
            at += channel.write(buf, at);
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
