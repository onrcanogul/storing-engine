package com.onurcanogul.bitcask.recovery;

import com.onurcanogul.bitcask.format.CorruptRecordException;
import com.onurcanogul.bitcask.format.FileHeader;
import com.onurcanogul.bitcask.format.LogRecord;
import com.onurcanogul.bitcask.format.RecordCodec;
import com.onurcanogul.bitcask.format.RecordType;
import com.onurcanogul.bitcask.index.KeyDirEntry;
import com.onurcanogul.bitcask.store.HintFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rebuilds the in-memory index by replaying every segment in order.
 *
 * <p>A later record supersedes an earlier one, so segment order followed by
 * physical order within each segment is already the correct order — no sorting
 * and no timestamps are involved.
 *
 * <p>Damage is judged differently depending on where it is found. Closed
 * segments were fsynced when they were rotated out, so they are known to have
 * reached the disk: a bad record there is real corruption and is never
 * tolerated. Only the active segment can hold the half-written tail a crash
 * leaves behind, and only there does {@link RecoveryMode#TOLERATE_TAIL} apply.
 *
 * <p>Internal to the engine.
 */
public final class Recovery {

    private static final int OFF_TYPE = 20;
    private static final int OFF_KEY_LEN = 21;
    private static final int OFF_VAL_LEN = 23;

    private Recovery() {
    }

    /**
     * @param directory where the segments and their hint files live
     * @param channels  open channels for every segment, keyed by file id
     * @param fileIds   segment ids in ascending order; the last one is active
     */
    public static RecoveryResult replay(Path directory,
                                        Map<Integer, FileChannel> channels,
                                        List<Integer> fileIds,
                                        Map<ByteBuffer, KeyDirEntry> keyDir,
                                        RecoveryMode mode) throws IOException {
        long recordsReplayed = 0;
        long maxSeq = 0;
        long segmentsFromHints = 0;
        Map<Integer, Long> deadBytes = new HashMap<>();

        int activeFileId = fileIds.get(fileIds.size() - 1);
        List<HintFile.Entry> activeEntries = new ArrayList<>();

        for (int fileId : fileIds) {
            boolean isActive = fileId == activeFileId;
            FileChannel channel = channels.get(fileId);

            // A closed segment with a usable hint is loaded without the log being
            // read at all. The active one never has one: it is still growing, so
            // no summary of it could be complete.
            if (!isActive) {
                Optional<List<HintFile.Entry>> hinted = usableHint(directory, fileId, channel.size(), maxSeq);
                if (hinted.isPresent()) {
                    for (HintFile.Entry entry : hinted.get()) {
                        apply(keyDir, deadBytes, entry.key(), entry.type(), entry.seq(),
                                fileId, entry.recordPos(), entry.recordSize());
                        maxSeq = entry.seq();
                    }
                    recordsReplayed += hinted.get().size();
                    segmentsFromHints++;
                    continue;
                }
            }

            ScanResult scan = scanSegment(channel, keyDir, deadBytes, fileId, maxSeq,
                    isActive ? activeEntries : null);
            recordsReplayed += scan.records();
            maxSeq = scan.maxSeq();

            long unreadable = channel.size() - scan.endOffset();
            if (unreadable == 0) {
                continue;
            }

            // Closed segments were fsynced at rotation. Damage there cannot be a
            // torn write, so it is never the expected trace of a crash.
            if (!isActive) {
                throw new IOException("segment " + fileId + " is damaged at offset "
                        + scan.endOffset() + " (" + scan.reason() + "): " + scan.detail()
                        + " — closed segments are fsynced, so this is real corruption");
            }
            if (mode == RecoveryMode.STRICT) {
                throw new IOException("active segment " + fileId + " is damaged at offset "
                        + scan.endOffset() + " (" + scan.reason() + "): " + scan.detail()
                        + " — " + unreadable + " bytes unreadable");
            }

            channel.truncate(scan.endOffset());

            RecoveryReport report = new RecoveryReport(recordsReplayed, keyDir.size(), unreadable,
                    scan.endOffset(), scan.reason(), segmentsFromHints);
            return new RecoveryResult(report, fileId, scan.endOffset(), maxSeq, deadBytes,
                    activeEntries);
        }

        long writePos = channels.get(activeFileId).size();
        RecoveryReport report = new RecoveryReport(recordsReplayed, keyDir.size(), 0, -1,
                StopReason.CLEAN_EOF, segmentsFromHints);

        return new RecoveryResult(report, activeFileId, writePos, maxSeq, deadBytes, activeEntries);
    }

    /** Where a segment scan stopped, and why. */
    private record ScanResult(long endOffset, long records, long maxSeq,
                              StopReason reason, String detail) {
    }

    /**
     * Reads a segment's hint, and vets it before a single entry is applied.
     *
     * <p>A hint is a cache, so anything doubtful about it is answered by reading
     * the log instead. {@link HintFile} has already proved the file is intact and
     * belongs to this segment; what is left is whether what it says is consistent
     * with the log it claims to describe. Checking that up front matters: half an
     * applied hint would leave the index in a state neither file describes.
     */
    private static Optional<List<HintFile.Entry>> usableHint(Path directory,
                                                             int fileId,
                                                             long segmentBytes,
                                                             long seqSoFar) throws IOException {
        Optional<List<HintFile.Entry>> hinted = HintFile.read(directory, fileId, segmentBytes);
        if (hinted.isEmpty()) {
            return Optional.empty();
        }

        long previousSeq = seqSoFar;
        long expectedPos = FileHeader.SIZE;

        for (HintFile.Entry entry : hinted.get()) {
            // The same rule the log is held to: sequence numbers only ever go up,
            // within a segment and across them.
            if (entry.seq() <= previousSeq) {
                return Optional.empty();
            }
            // Records sit end to end from the header onwards, with no gaps. An
            // entry pointing anywhere else describes some other file.
            if (entry.recordPos() != expectedPos || entry.recordSize() <= 0
                    || entry.recordPos() + entry.recordSize() > segmentBytes) {
                return Optional.empty();
            }
            previousSeq = entry.seq();
            expectedPos = entry.recordPos() + entry.recordSize();
        }

        // The hint has to account for the whole segment. Anything left over is a
        // record it failed to mention, and startup would never learn of it.
        if (expectedPos != segmentBytes) {
            return Optional.empty();
        }

        return hinted;
    }

    /**
     * @param entriesOut collects what was found, or null when nobody needs it.
     *                   Only the active segment does: its records are the start of
     *                   the hint that will be written when it rotates.
     */
    private static ScanResult scanSegment(FileChannel channel,
                                          Map<ByteBuffer, KeyDirEntry> keyDir,
                                          Map<Integer, Long> deadBytes,
                                          int fileId,
                                          long seqSoFar,
                                          List<HintFile.Entry> entriesOut) throws IOException {
        long fileSize = channel.size();
        long position = FileHeader.SIZE;
        long records = 0;
        long maxSeq = seqSoFar;

        ByteBuffer header = ByteBuffer.allocate(RecordCodec.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);

        while (position < fileSize) {
            header.clear();
            if (readAt(channel, header, position) < RecordCodec.HEADER_SIZE) {
                return new ScanResult(position, records, maxSeq,
                        StopReason.SHORT_READ, "header cut short");
            }
            header.flip();

            byte typeCode = header.get(OFF_TYPE);
            int keyLen = header.getShort(OFF_KEY_LEN) & 0xFFFF;
            long valLen = header.getInt(OFF_VAL_LEN) & 0xFFFFFFFFL;

            try {
                RecordCodec.validateHeaderFields(typeCode, keyLen, valLen, fileSize - position);
            } catch (CorruptRecordException e) {
                return new ScanResult(position, records, maxSeq,
                        StopReason.INVALID_HEADER_FIELD, e.getMessage());
            }

            int size = RecordCodec.recordSize(keyLen, (int) valLen);
            ByteBuffer full = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
            if (readAt(channel, full, position) < size) {
                return new ScanResult(position, records, maxSeq,
                        StopReason.SHORT_READ, "record cut short");
            }
            full.flip();

            LogRecord record;
            try {
                record = RecordCodec.decode(full);
            } catch (CorruptRecordException e) {
                return new ScanResult(position, records, maxSeq, StopReason.CRC_MISMATCH, e.getMessage());
            }

            // A correct writer always advances the sequence number, across
            // segments as well as within one.
            if (record.seq() <= maxSeq) {
                return new ScanResult(position, records, maxSeq, StopReason.NON_INCREASING_SEQ,
                        "seq " + record.seq() + " does not follow " + maxSeq);
            }

            apply(keyDir, deadBytes, record.key(), record.type(), record.seq(),
                    fileId, position, size);
            if (entriesOut != null) {
                entriesOut.add(new HintFile.Entry(record.seq(), record.type(), position, size,
                        Arrays.copyOf(record.key(), record.key().length)));
            }

            maxSeq = record.seq();
            records++;
            position += size;
        }

        return new ScanResult(position, records, maxSeq, StopReason.CLEAN_EOF, null);
    }

    /**
     * Puts one record into the index, from a log or from a hint.
     *
     * <p>Takes the pieces rather than a {@code LogRecord} because a hint has no
     * value to give: leaving both callers on one path is what makes a hinted
     * startup and a scanned one produce the same index, garbage counters and all.
     */
    private static void apply(Map<ByteBuffer, KeyDirEntry> keyDir,
                              Map<Integer, Long> deadBytes,
                              byte[] recordKey, RecordType type, long seq,
                              int fileId, long position, int size) {
        ByteBuffer key = ByteBuffer.wrap(Arrays.copyOf(recordKey, recordKey.length));

        KeyDirEntry superseded = (type == RecordType.PUT)
                ? keyDir.put(key, new KeyDirEntry(fileId, position, size, seq))
                : keyDir.remove(key);

        // Whatever this record replaced is now garbage, and it is garbage in
        // whichever segment happens to hold it.
        if (superseded != null) {
            deadBytes.merge(superseded.fileId(), (long) superseded.recordSize(), Long::sum);
        }
    }

    private static int readAt(FileChannel channel, ByteBuffer buf, long position) throws IOException {
        int total = 0;
        long at = position;
        while (buf.hasRemaining()) {
            int read = channel.read(buf, at);
            if (read < 0) {
                break;
            }
            total += read;
            at += read;
        }
        return total;
    }
}
