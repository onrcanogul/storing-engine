package com.onurcanogul.bitcask.recovery;

import com.onurcanogul.bitcask.format.CorruptRecordException;
import com.onurcanogul.bitcask.format.FileHeader;
import com.onurcanogul.bitcask.format.LogRecord;
import com.onurcanogul.bitcask.format.RecordCodec;
import com.onurcanogul.bitcask.format.RecordType;
import com.onurcanogul.bitcask.index.KeyDirEntry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
     * @param channels open channels for every segment, keyed by file id
     * @param fileIds  segment ids in ascending order; the last one is active
     */
    public static RecoveryResult replay(Map<Integer, FileChannel> channels,
                                        List<Integer> fileIds,
                                        Map<ByteBuffer, KeyDirEntry> keyDir,
                                        RecoveryMode mode) throws IOException {
        long recordsReplayed = 0;
        long maxSeq = 0;

        int activeFileId = fileIds.get(fileIds.size() - 1);

        for (int fileId : fileIds) {
            boolean isActive = fileId == activeFileId;
            FileChannel channel = channels.get(fileId);

            ScanResult scan = scanSegment(channel, keyDir, fileId, maxSeq);
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

            RecoveryReport report = new RecoveryReport(
                    recordsReplayed, keyDir.size(), unreadable, scan.endOffset(), scan.reason());
            return new RecoveryResult(report, fileId, scan.endOffset(), maxSeq);
        }

        long writePos = channels.get(activeFileId).size();
        RecoveryReport report = new RecoveryReport(
                recordsReplayed, keyDir.size(), 0, -1, StopReason.CLEAN_EOF);

        return new RecoveryResult(report, activeFileId, writePos, maxSeq);
    }

    /** Where a segment scan stopped, and why. */
    private record ScanResult(long endOffset, long records, long maxSeq,
                              StopReason reason, String detail) {
    }

    private static ScanResult scanSegment(FileChannel channel,
                                          Map<ByteBuffer, KeyDirEntry> keyDir,
                                          int fileId,
                                          long seqSoFar) throws IOException {
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

            apply(keyDir, record, fileId, position, size);

            maxSeq = record.seq();
            records++;
            position += size;
        }

        return new ScanResult(position, records, maxSeq, StopReason.CLEAN_EOF, null);
    }

    private static void apply(Map<ByteBuffer, KeyDirEntry> keyDir, LogRecord record,
                              int fileId, long position, int size) {
        ByteBuffer key = ByteBuffer.wrap(Arrays.copyOf(record.key(), record.key().length));

        if (record.type() == RecordType.PUT) {
            keyDir.put(key, new KeyDirEntry(fileId, position, size, record.seq()));
        } else {
            keyDir.remove(key);
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
