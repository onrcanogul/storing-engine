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
import java.util.Map;

/**
 * Rebuilds the in-memory index by replaying the log from beginning to end.
 *
 * <p>A later record supersedes an earlier one, so the physical order in the file
 * is already the correct order — no sorting and no timestamps are involved.
 *
 * <p>Validation runs cheapest-first and allocates nothing until every length
 * field has been checked, so a corrupt length can never size an array.
 *
 * <p>Internal to the engine.
 */
public final class Recovery {

    /** Offsets of the header fields the scanner reads before trusting anything. */
    private static final int OFF_TYPE = 20;
    private static final int OFF_KEY_LEN = 21;
    private static final int OFF_VAL_LEN = 23;

    private Recovery() {
    }

    public static RecoveryResult replay(FileChannel channel,
                                        Map<ByteBuffer, KeyDirEntry> keyDir,
                                        RecoveryMode mode) throws IOException {
        long fileSize = channel.size();
        long position = FileHeader.SIZE;
        long recordsReplayed = 0;
        long maxSeq = 0;

        StopReason reason = StopReason.CLEAN_EOF;
        String detail = null;

        ByteBuffer header = ByteBuffer.allocate(RecordCodec.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);

        while (position < fileSize) {
            header.clear();
            if (readAt(channel, header, position) < RecordCodec.HEADER_SIZE) {
                reason = StopReason.SHORT_READ;
                detail = "header cut short";
                break;
            }
            header.flip();

            byte typeCode = header.get(OFF_TYPE);
            int keyLen = header.getShort(OFF_KEY_LEN) & 0xFFFF;
            long valLen = header.getInt(OFF_VAL_LEN) & 0xFFFFFFFFL;

            try {
                RecordCodec.validateHeaderFields(typeCode, keyLen, valLen, fileSize - position);
            } catch (CorruptRecordException e) {
                reason = StopReason.INVALID_HEADER_FIELD;
                detail = e.getMessage();
                break;
            }

            int size = RecordCodec.recordSize(keyLen, (int) valLen);
            ByteBuffer full = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
            if (readAt(channel, full, position) < size) {
                reason = StopReason.SHORT_READ;
                detail = "record cut short";
                break;
            }
            full.flip();

            LogRecord record;
            try {
                record = RecordCodec.decode(full);
            } catch (CorruptRecordException e) {
                reason = StopReason.CRC_MISMATCH;
                detail = e.getMessage();
                break;
            }

            // A correct writer always advances the sequence number, so this can
            // only mean corruption or a file that is not what it claims to be.
            if (record.seq() <= maxSeq) {
                reason = StopReason.NON_INCREASING_SEQ;
                detail = "seq " + record.seq() + " does not follow " + maxSeq;
                break;
            }

            apply(keyDir, record, position, size);

            maxSeq = record.seq();
            recordsReplayed++;
            position += size;
        }

        long bytesDiscarded = fileSize - position;

        if (bytesDiscarded > 0 && mode == RecoveryMode.STRICT) {
            // Refuse without touching the file: whoever investigates needs the evidence.
            throw new IOException("log damaged at offset " + position
                    + " (" + reason + "): " + detail
                    + " — " + bytesDiscarded + " bytes unreadable");
        }
        if (bytesDiscarded > 0) {
            channel.truncate(position);
        }

        RecoveryReport report = new RecoveryReport(
                recordsReplayed,
                keyDir.size(),
                bytesDiscarded,
                bytesDiscarded > 0 ? position : -1,
                reason);

        return new RecoveryResult(report, position, maxSeq);
    }

    private static void apply(Map<ByteBuffer, KeyDirEntry> keyDir,
                              LogRecord record, long position, int size) {
        ByteBuffer key = ByteBuffer.wrap(Arrays.copyOf(record.key(), record.key().length));

        if (record.type() == RecordType.PUT) {
            keyDir.put(key, new KeyDirEntry(0, position, size, record.seq()));
        } else {
            keyDir.remove(key);
        }
    }

    /** @return how many bytes were actually read, which may be short at end of file */
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
