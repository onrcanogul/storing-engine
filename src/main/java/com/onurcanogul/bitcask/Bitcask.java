package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.compaction.CompactionStats;
import com.onurcanogul.bitcask.compaction.MergeReport;
import com.onurcanogul.bitcask.compaction.SegmentStats;
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
import com.onurcanogul.bitcask.store.HintFile;
import com.onurcanogul.bitcask.store.HintWriter;
import com.onurcanogul.bitcask.store.OpenFileLimit;
import com.onurcanogul.bitcask.store.SegmentFiles;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
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

    /**
     * Every segment stays open: the index points into all of them.
     *
     * <p>Concurrent because readers look segments up without the write lock,
     * while rotation and merging add and remove entries.
     */
    private final Map<Integer, FileChannel> channels;

    /** Serialises merges against each other without blocking ordinary writes. */
    private final Object mergeLock = new Object();

    /** Writes hint files off the write lock, since nothing waits on a cache. */
    private final HintWriter hintWriter;

    /**
     * How many records a merge carries forward per acquisition of the write lock.
     *
     * <p>Taking the lock once per record loses to a writer that never pauses.
     * {@code synchronized} makes no fairness promise, and the thread that just
     * released a lock is the one still running, so it usually takes it straight
     * back. Measured, the merge got a turn about once per rotation the writer
     * performed — a few hundred records a second — which is not compaction
     * keeping up, it is compaction losing slowly.
     *
     * <p>Batching does not change how often the merge wins the lock. It changes
     * how far it gets each time.
     *
     * <p>Swept against a writer that never pauses, merging 2,000 live records out
     * of 8,000: 20.2 s at a batch of 1, 4.9 s at 8, 3.6 s at 64, 3.4 s at 512.
     * Sixty-four sits at the knee — past it the gain is single digits while the
     * lock is held far longer.
     *
     * <p>The cost this was expected to have did not appear. The writer's latency
     * was unchanged across the whole sweep (p99 within 5,075 µs and 4,714 µs, and
     * moving the wrong way for batching to explain it), because what dominates a
     * writer's tail is its own rotation, not waiting for a merge. That may not
     * hold on a machine where rotations are rarer, which is the reason to stay at
     * the knee rather than take the last 6%.
     */
    private static final int MERGE_BATCH_RECORDS = 64;

    /**
     * How many hint entries may be held in memory for one segment.
     *
     * <p>A segment is bounded in bytes, not in records, so a store of small
     * records can hold millions in one file. At roughly eighty bytes an entry
     * that is heap the caller never asked to spend, so past this point the list
     * is abandoned and the hint is built by reading the segment at rotation.
     */
    private static final int MAX_PENDING_HINT_ENTRIES = 1_000_000;

    /**
     * Called at named points inside a merge. Does nothing unless a test says so.
     *
     * <p>A merge crash cannot be staged from the outside: the interesting moments
     * are between a copy and a delete, and they pass in microseconds. This is the
     * seam a crash test halts the JVM on. Production never sets it.
     */
    volatile Consumer<String> mergeHook = point -> {
    };

    /**
     * How many times the active segment has been fsynced.
     *
     * <p>Exists because durability is an ordering property, and ordering is not
     * observable from the outside: killing a process does not empty the page
     * cache, so a missing fsync looks exactly like a present one. Counting the
     * calls is the only way a test can hold the engine to the order it promises.
     */
    final AtomicLong syncCount = new AtomicLong();

    /**
     * Bytes per segment held by records that have since been superseded.
     *
     * <p>Counted as writes happen rather than computed on demand: working it out
     * from the index would cost a pass over every key, and keys are the one thing
     * this engine has in quantity.
     */
    private final Map<Integer, AtomicLong> deadBytes;

    /** Read by {@code get} and {@code compactionStats} without the write lock. */
    private volatile int activeFileId;

    private FileChannel activeChannel;
    private long writePos;
    private long nextSeq;

    /**
     * The hint entries for the active segment, collected as it is written.
     *
     * <p>The writer already knows everything a hint holds — key, offset, length,
     * sequence number — at the moment it appends a record. Remembering them costs
     * nothing and spares the segment from being read back at rotation just to
     * learn what was written into it.
     *
     * <p>Guarded by the write lock, like {@code writePos}: both {@code append}
     * and the merge's own append run under it.
     */
    private List<HintFile.Entry> pendingHints;

    /**
     * Whether {@link #pendingHints} still covers the whole active segment.
     *
     * <p>It stops covering it when the segment holds more records than the cap
     * allows, at which point the list is dropped rather than allowed to grow
     * without bound. The hint is then built by reading the segment back at
     * rotation — slower, but a segment that large is exactly the one worth having
     * a hint for.
     */
    private boolean pendingHintsComplete;

    private volatile boolean closed;

    private Bitcask(Path directory,
                    BitcaskConfig config,
                    DirectoryLock lock,
                    Map<Integer, FileChannel> channels,
                    Map<ByteBuffer, KeyDirEntry> keyDir,
                    Map<Integer, Long> deadBytes,
                    RecoveryReport report,
                    int activeFileId,
                    long writePos,
                    long nextSeq,
                    List<HintFile.Entry> activeSegmentEntries) {
        this.deadBytes = new ConcurrentHashMap<>();
        deadBytes.forEach((fileId, bytes) -> this.deadBytes.put(fileId, new AtomicLong(bytes)));
        this.directory = directory;
        this.hintWriter = new HintWriter(directory);
        this.config = config;
        this.lock = lock;
        this.channels = channels;
        this.keyDir = keyDir;
        this.report = report;
        this.activeFileId = activeFileId;
        this.activeChannel = channels.get(activeFileId);
        this.writePos = writePos;
        this.nextSeq = nextSeq;

        // Picking up where the last run left off. A hint written from an empty
        // list here would describe only this run's writes while claiming the
        // whole segment, and everything older in it would be lost at the next
        // startup.
        this.pendingHints = new ArrayList<>(activeSegmentEntries);
        this.pendingHintsComplete = activeSegmentEntries.size() <= MAX_PENDING_HINT_ENTRIES;
        if (!pendingHintsComplete) {
            pendingHints = new ArrayList<>();
        }
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

        Map<Integer, FileChannel> channels = new ConcurrentHashMap<>();
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
            RecoveryResult recovered = Recovery.replay(dir, channels, fileIds, keyDir,
                    config.recoveryMode());

            return new Bitcask(dir, config, lock, channels, keyDir, recovered.deadBytes(),
                    recovered.report(), recovered.activeFileId(),
                    recovered.writePos(), recovered.maxSeq() + 1,
                    recovered.activeSegmentEntries());

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
                (position, size, seq) -> recordSuperseded(keyDir.put(indexKeyCopyOf(key),
                        new KeyDirEntry(activeFileId, position, size, seq))));
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
                (position, size, seq) -> recordSuperseded(keyDir.remove(lookupKey)));
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
            forceActive();
        }

        // Disk before memory. In the other order a failed write would leave the
        // index pointing at a record that does not exist.
        indexUpdate.apply(position, size, seq);
        rememberForHint(seq, type, position, size, key);

        // Advanced last, so a failed write leaves no gap in the log.
        writePos = position + size;
        nextSeq = seq + 1;
    }

    /**
     * Pushes the active segment to the disk itself, not just to the page cache.
     *
     * <p>Every fsync in the engine goes through here so that {@link #syncCount}
     * stays honest.
     */
    private void forceActive() throws IOException {
        activeChannel.force(false);
        syncCount.incrementAndGet();
    }

    /**
     * Notes a record for the hint of the segment being written.
     *
     * <p>Gives up rather than growing past the cap: past that point the entries
     * cost more memory than the hint is worth carrying, and the segment can be
     * read back at rotation instead.
     */
    private void rememberForHint(long seq, RecordType type, long position, int size, byte[] key) {
        if (!pendingHintsComplete) {
            return;
        }
        if (pendingHints.size() >= MAX_PENDING_HINT_ENTRIES) {
            pendingHints = new ArrayList<>();
            pendingHintsComplete = false;
            return;
        }
        pendingHints.add(new HintFile.Entry(seq, type, position, size,
                Arrays.copyOf(key, key.length)));
    }

    /**
     * Hands the hint for the segment that has just stopped taking writes to the
     * background writer.
     *
     * <p>Everything expensive about a hint — building the file, fsyncing it,
     * moving it into place — happens on the other side of this call, because the
     * write lock is held here and no writer should wait on a cache.
     *
     * <p>{@code writePos} rather than the file's length: they are the same number
     * and one of them is a syscall.
     *
     * <p>A failure is not allowed to fail the rotation. Without a hint the next
     * startup reads this segment's log, which is what every startup did before
     * hints existed.
     */
    private void handOverHintFor(int fileId) {
        try {
            List<HintFile.Entry> entries = pendingHintsComplete
                    ? pendingHints
                    : entriesByReading(fileId);

            hintWriter.submit(fileId, entries, writePos);
        } catch (IOException doNotFailTheWriteOverACache) {
            // Only reachable on the rebuild-by-reading path. Carry on.
        } finally {
            // A fresh list either way: the old one now belongs to the writer.
            pendingHints = new ArrayList<>();
            pendingHintsComplete = true;
        }
    }

    /** Rebuilds a segment's hint entries by reading it, when memory could not hold them. */
    private List<HintFile.Entry> entriesByReading(int fileId) throws IOException {
        List<HintFile.Entry> entries = new ArrayList<>();
        for (SourceRecord source : readSegment(fileId)) {
            LogRecord record = source.record();
            entries.add(new HintFile.Entry(record.seq(), record.type(),
                    source.position(), source.size(),
                    Arrays.copyOf(record.key(), record.key().length)));
        }
        return entries;
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
        forceActive();

        // Only now, with the segment on the disk: a hint that reached the disk
        // first would, after a power failure, point at records that never made
        // it. Handed over rather than written here — see HintWriter.
        handOverHintFor(activeFileId);

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

        ByteBuffer lookupKey = ByteBuffer.wrap(key);
        KeyDirEntry entry = keyDir.get(lookupKey);

        while (true) {
            if (entry == null) {
                return null;
            }
            try {
                return readAt(entry, key);
            } catch (SegmentGoneException e) {
                // A merge copied this record elsewhere and dropped the segment it
                // used to live in. Nothing is wrong; the entry in hand is stale.
                KeyDirEntry current = keyDir.get(lookupKey);
                if (entry.equals(current)) {
                    throw new IOException("segment " + entry.fileId()
                            + " is gone but the index still points at it: internal inconsistency", e);
                }
                entry = current;
            }
        }
    }

    /** Raised when the segment an entry names has been merged away mid-read. */
    private static final class SegmentGoneException extends IOException {
        SegmentGoneException(String message) {
            super(message);
        }
    }

    private byte[] readAt(KeyDirEntry entry, byte[] key) throws IOException {
        FileChannel channel = channels.get(entry.fileId());
        if (channel == null) {
            throw new SegmentGoneException("segment " + entry.fileId() + " is no longer open");
        }

        ByteBuffer buf = ByteBuffer.allocate(entry.recordSize());
        try {
            readFully(channel, buf, entry.recordPos());
        } catch (ClosedChannelException e) {
            throw new SegmentGoneException("segment " + entry.fileId() + " closed while being read");
        }
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


    /**
     * Reclaims space by rewriting live records and dropping the segments that
     * held them.
     *
     * <p>Live records are copied forward through the ordinary write path, into
     * the active segment with fresh sequence numbers, rather than into a separate
     * merge file. That keeps a rule the whole design leans on: a later record is
     * a newer record. A merge file would carry old data under a new file id, and
     * recovery — which walks segments in order — would then see sequence numbers
     * going backwards, or worse, let a stale value overwrite a current one.
     *
     * <p>Each record is examined and copied under the write lock, but the lock is
     * released between records, so ordinary writes are interleaved rather than
     * blocked. If a key is rewritten while its old record is being considered,
     * the liveness check sees that the index has moved on and skips it — a stale
     * value can never come back.
     *
     * <p>Segments are deleted only after everything live has been copied. A crash
     * partway through leaves duplicates, not gaps: the copies carry higher
     * sequence numbers, so recovery prefers them, and the originals become
     * garbage for the next merge to collect.
     *
     * @return what was done, or {@link MergeReport#nothingToDo()} if no segment
     *         was dirty enough to be worth rewriting
     */
    public MergeReport merge() throws IOException {
        ensureOpen();
        synchronized (mergeLock) {
            return runMerge();
        }
    }

    private MergeReport runMerge() throws IOException {
        // One snapshot for both decisions below, so they cannot disagree about
        // which segments exist while a writer is rotating underneath us.
        CompactionStats stats = compactionStats();
        List<Integer> candidates = stats.mergeCandidates(config.mergeThreshold())
                .stream()
                .map(SegmentStats::fileId)
                .toList();

        if (candidates.isEmpty()) {
            return MergeReport.nothingToDo();
        }

        // The oldest closed segment this merge is leaving behind. A tombstone in
        // an older segment than this one has nothing left to cancel: every
        // segment that could still hold its PUT is about to be deleted.
        Set<Integer> candidateSet = new HashSet<>(candidates);
        int oldestSurvivor = stats.segments().stream()
                .filter(segment -> !segment.active() && !candidateSet.contains(segment.fileId()))
                .mapToInt(SegmentStats::fileId)
                .min()
                .orElse(Integer.MAX_VALUE);

        long bytesBefore = 0;
        for (int fileId : candidates) {
            bytesBefore += channels.get(fileId).size();
        }

        long moved = 0;
        long discarded = 0;
        long bytesWritten = 0;

        // One tombstone per key is enough to keep a deletion alive.
        Set<ByteBuffer> tombstonesKept = new HashSet<>();

        for (int fileId : candidates) {
            // Read outside the lock, then hand the records over in batches. The
            // reading was never the contended part; the deciding and copying are.
            List<SourceRecord> records = readSegment(fileId);

            for (int from = 0; from < records.size(); from += MERGE_BATCH_RECORDS) {
                int to = Math.min(from + MERGE_BATCH_RECORDS, records.size());
                Tally tally = copyBatchForward(records.subList(from, to),
                        tombstonesKept, oldestSurvivor);

                moved += tally.moved();
                discarded += tally.discarded();
                bytesWritten += tally.bytesWritten();
            }
        }

        // Durability ordering, and the one step this whole method rests on: a
        // source segment may only be deleted once the copies taken out of it are
        // on the disk. Skip it and a power failure here takes both the copy and
        // the original, which turns compaction into data loss. Rotation has
        // already fsynced every earlier output segment, so one call covers the
        // rest of them.
        syncMergeOutput();

        mergeHook.accept(MergeHookPoint.BEFORE_DROP);

        boolean firstDropped = false;
        for (int fileId : candidates) {
            dropSegment(fileId);
            if (!firstDropped) {
                firstDropped = true;
                mergeHook.accept(MergeHookPoint.MID_DROP);
            }
        }

        return new MergeReport(candidates, moved, discarded, bytesBefore - bytesWritten);
    }

    /**
     * Fsyncs the segment the merge has been copying into.
     *
     * <p>Synchronized because {@code activeChannel} belongs to the writer: a
     * rotation could be swapping it out at this very moment, and forcing the
     * channel that is on its way out would prove nothing about the new one.
     */
    private synchronized void syncMergeOutput() throws IOException {
        forceActive();
    }

    /** Where inside a merge {@link #mergeHook} is called. */
    static final class MergeHookPoint {
        /** A record has just been copied forward; the rest have not. */
        static final String MID_COPY = "mid-copy";
        /** Every live record is copied; no source segment is deleted yet. */
        static final String BEFORE_DROP = "before-drop";
        /** The first source segment is deleted; the others are still there. */
        static final String MID_DROP = "mid-drop";

        private MergeHookPoint() {
        }
    }

    /** One record read out of a segment being merged. */
    private record SourceRecord(int fileId, long position, int size, LogRecord record) {
    }

    /** What one batch of the merge managed to do. */
    private record Tally(long moved, long discarded, long bytesWritten) {
    }

    /**
     * Carries a batch of records forward under one acquisition of the write lock.
     *
     * <p>The lock has to cover the liveness check and the copy together — a
     * competing {@code put} landing between them would make the record stale and
     * copying it anyway would resurrect an old value. Covering a whole batch is
     * the same guarantee, held for longer.
     */
    private synchronized Tally copyBatchForward(List<SourceRecord> batch,
                                                Set<ByteBuffer> tombstonesKept,
                                                int oldestSurvivor)
            throws IOException {
        long moved = 0;
        long discarded = 0;
        long bytesWritten = 0;

        for (SourceRecord source : batch) {
            long written = copyForwardIfStillNeeded(source, tombstonesKept, oldestSurvivor);
            if (written > 0) {
                moved++;
                bytesWritten += written;
                mergeHook.accept(MergeHookPoint.MID_COPY);
            } else {
                discarded++;
            }
        }

        return new Tally(moved, discarded, bytesWritten);
    }

    /**
     * Copies a record forward if the index still needs it.
     *
     * <p>Called with the write lock held, by {@link #copyBatchForward}.
     *
     * @return bytes written, or 0 if the record was not worth keeping
     */
    private long copyForwardIfStillNeeded(SourceRecord source,
                                                       Set<ByteBuffer> tombstonesKept,
                                                       int oldestSurvivor)
            throws IOException {
        LogRecord record = source.record();
        ByteBuffer key = ByteBuffer.wrap(record.key());
        KeyDirEntry current = keyDir.get(key);

        if (record.type() == RecordType.TOMBSTONE) {
            // The key is live again, so a later PUT already overrode this
            // tombstone and it has nothing left to cancel.
            if (current != null) {
                return 0;
            }
            // Nothing older than this segment survives the merge, so no PUT is
            // left for the tombstone to cancel and it can finally be dropped.
            // Without this, deletions would be paid for on every future merge.
            if (source.fileId() < oldestSurvivor) {
                return 0;
            }
            // An older segment does survive, so keep exactly one copy: dropping
            // them all would let the PUT still sitting there resurrect the key
            // on the next replay.
            if (!tombstonesKept.add(ByteBuffer.wrap(Arrays.copyOf(record.key(), record.key().length)))) {
                return 0;
            }
            return appendDuringMerge(RecordType.TOMBSTONE, record.key(), new byte[0], null);
        }

        // Position, not sequence: this asks whether the index points at exactly
        // this record, which also rules out another copy of the same key.
        boolean stillCurrent = current != null
                && current.fileId() == source.fileId()
                && current.recordPos() == source.position();

        if (!stillCurrent) {
            return 0;
        }
        return appendDuringMerge(RecordType.PUT, record.key(), record.value(), key);
    }

    /**
     * Appends a record on behalf of the merge.
     *
     * <p>Deliberately does not go through {@link #recordSuperseded}: the record
     * being replaced lives in a segment that is about to be deleted, so counting
     * it as garbage would inflate a number that is about to disappear.
     */
    private long appendDuringMerge(RecordType type, byte[] key, byte[] value, ByteBuffer indexKey)
            throws IOException {
        int size = RecordCodec.recordSize(key.length, value.length);
        if (writePos + size > config.maxSegmentSize()) {
            rotate();
        }

        long seq = nextSeq;
        ByteBuffer record = RecordCodec.encode(seq, System.currentTimeMillis(), type, key, value);
        long position = writePos;

        writeFully(record, position);
        if (config.syncPolicy() == SyncPolicy.ALWAYS) {
            forceActive();
        }

        if (indexKey != null) {
            keyDir.put(indexKeyCopyOf(key), new KeyDirEntry(activeFileId, position, size, seq));
        }
        rememberForHint(seq, type, position, size, key);

        writePos = position + size;
        nextSeq = seq + 1;
        return size;
    }

    /** Reads every record in a segment, so the merge can decide about each one. */
    private List<SourceRecord> readSegment(int fileId) throws IOException {
        FileChannel channel = channels.get(fileId);
        List<SourceRecord> records = new ArrayList<>();

        long size = channel.size();
        long position = FileHeader.SIZE;

        while (position < size) {
            ByteBuffer header = ByteBuffer.allocate(RecordCodec.HEADER_SIZE);
            readFully(channel, header, position);
            header.flip();

            int keyLen = header.getShort(21) & 0xFFFF;
            long valLen = header.getInt(23) & 0xFFFFFFFFL;
            int recordSize = RecordCodec.recordSize(keyLen, (int) valLen);

            ByteBuffer full = ByteBuffer.allocate(recordSize);
            readFully(channel, full, position);
            full.flip();

            records.add(new SourceRecord(fileId, position, recordSize, RecordCodec.decode(full)));
            position += recordSize;
        }
        return records;
    }

    /** Closes and deletes a segment whose live records have all been copied out. */
    private synchronized void dropSegment(int fileId) throws IOException {
        FileChannel channel = channels.remove(fileId);
        deadBytes.remove(fileId);

        if (channel != null) {
            channel.close();
        }
        Files.deleteIfExists(SegmentFiles.pathOf(directory, fileId));

        // A hint outliving its segment would describe a file that no longer
        // exists, and the id it carries could later be handed to a new segment.
        HintFile.delete(directory, fileId);
    }

    /**
     * Notes that {@code superseded} is now garbage, in whichever segment holds it.
     *
     * <p>The tombstone or newer record that displaced it is not counted here: it
     * is still live as far as the index is concerned, and a tombstone still has
     * work to do until compaction can prove otherwise.
     */
    private void recordSuperseded(KeyDirEntry superseded) {
        if (superseded == null) {
            return;
        }
        deadBytes.computeIfAbsent(superseded.fileId(), id -> new AtomicLong())
                .addAndGet(superseded.recordSize());
    }

    /**
     * How much of the store is garbage, segment by segment.
     *
     * <p>Cheap to call: the counts are maintained as writes happen. Use it to
     * decide whether {@link #merge()} is worth running.
     */
    public synchronized CompactionStats compactionStats() throws IOException {
        ensureOpen();

        List<SegmentStats> segments = new ArrayList<>(channels.size());
        for (Map.Entry<Integer, FileChannel> entry : channels.entrySet()) {
            int fileId = entry.getKey();
            AtomicLong dead = deadBytes.get(fileId);

            segments.add(new SegmentStats(
                    fileId,
                    entry.getValue().size(),
                    dead == null ? 0L : dead.get(),
                    fileId == activeFileId));
        }
        segments.sort(java.util.Comparator.comparingInt(SegmentStats::fileId));
        return new CompactionStats(segments);
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
            // Before the channels go: a clean shutdown keeps the hints it has
            // already built, so the next startup is the fast one.
            hintWriter.close();

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
