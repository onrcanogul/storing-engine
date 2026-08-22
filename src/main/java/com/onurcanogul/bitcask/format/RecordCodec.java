package com.onurcanogul.bitcask.format;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

/**
 * Turns records into bytes and back.
 *
 * <p>On-disk layout, big-endian:
 *
 * <pre>
 *   offset  size  field
 *        0     4  crc32c    checksum of every byte after itself
 *        4     8  seq       monotonic sequence number, the sole ordering authority
 *       12     8  tstamp    epoch millis, informational only, never compared
 *       20     1  type      1 = PUT, 2 = TOMBSTONE
 *       21     2  keyLen    unsigned, 1..65535
 *       23     4  valLen    unsigned
 *       27   ...  key
 *      ...   ...  value
 * </pre>
 *
 * <p>Internal to the engine.
 */
public final class RecordCodec {

    public static final int HEADER_SIZE = 27;

    private static final int OFF_CRC = 0;
    private static final int OFF_SEQ = 4;
    private static final int OFF_TSTAMP = 12;
    private static final int OFF_TYPE = 20;
    private static final int OFF_KEY_LEN = 21;
    private static final int OFF_VAL_LEN = 23;

    /** Everything from here to the end of the record is covered by the checksum. */
    private static final int CRC_COVERAGE_START = 4;

    private RecordCodec() {
    }

    public static int recordSize(int keyLen, int valLen) {
        return HEADER_SIZE + keyLen + valLen;
    }

    /**
     * Encodes one record. The returned buffer is positioned at 0 and holds
     * exactly {@code recordSize(key.length, value.length)} bytes.
     */
    public static ByteBuffer encode(long seq, long tstamp, RecordType type, byte[] key, byte[] value) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("key must not be empty");
        }
        if (key.length > FormatLimits.MAX_KEY_SIZE) {
            throw new IllegalArgumentException(
                    "key too long: " + key.length + " > " + FormatLimits.MAX_KEY_SIZE);
        }
        byte[] val = (value == null) ? new byte[0] : value;

        ByteBuffer buf = ByteBuffer.allocate(recordSize(key.length, val.length))
                .order(ByteOrder.BIG_ENDIAN);

        buf.position(OFF_SEQ);
        buf.putLong(seq);
        buf.putLong(tstamp);
        buf.put(type.code());
        buf.putShort((short) key.length);
        buf.putInt(val.length);
        buf.put(key);
        buf.put(val);

        buf.putInt(OFF_CRC, checksumOf(buf, buf.capacity()));

        buf.position(0);
        buf.limit(buf.capacity());
        return buf;
    }

    /**
     * Checks the header fields that decide how many bytes to read next.
     *
     * <p>Runs before anything is allocated. A corrupt length field must never
     * reach {@code new byte[...]}: a single bad byte would otherwise be able to
     * request gigabytes and take the process down with an
     * {@link OutOfMemoryError}.
     *
     * @param bytesRemainingInFile bytes available from the start of this record
     */
    public static void validateHeaderFields(byte typeCode, int keyLen, long valLen,
                                            long bytesRemainingInFile) throws CorruptRecordException {
        if (keyLen <= 0 || keyLen > FormatLimits.MAX_KEY_SIZE) {
            throw new CorruptRecordException("invalid keyLen: " + keyLen);
        }

        RecordType.fromCode(typeCode);

        if (valLen < 0 || valLen > FormatLimits.HARD_MAX_VALUE_SIZE) {
            throw new CorruptRecordException("invalid valLen: " + valLen);
        }

        // The strongest check, and the only one independent of configuration:
        // a record cannot be longer than what is left of its own file.
        long claimed = (long) HEADER_SIZE + keyLen + valLen;
        if (claimed > bytesRemainingInFile) {
            throw new CorruptRecordException(
                    "record claims " + claimed + " bytes but only " + bytesRemainingInFile + " remain");
        }
    }

    /** Decodes a buffer holding exactly one complete record starting at position 0. */
    public static LogRecord decode(ByteBuffer record) throws CorruptRecordException {
        ByteBuffer buf = record.duplicate().order(ByteOrder.BIG_ENDIAN);
        buf.position(0);

        if (buf.remaining() < HEADER_SIZE) {
            throw new CorruptRecordException("buffer shorter than a header: " + buf.remaining());
        }

        int storedCrc = buf.getInt(OFF_CRC);
        long seq = buf.getLong(OFF_SEQ);
        long tstamp = buf.getLong(OFF_TSTAMP);
        byte typeCode = buf.get(OFF_TYPE);
        int keyLen = buf.getShort(OFF_KEY_LEN) & 0xFFFF;
        long valLen = buf.getInt(OFF_VAL_LEN) & 0xFFFFFFFFL;

        validateHeaderFields(typeCode, keyLen, valLen, buf.limit());

        int total = recordSize(keyLen, (int) valLen);
        if (checksumOf(buf, total) != storedCrc) {
            throw new CorruptRecordException("crc mismatch at seq " + seq);
        }

        byte[] key = new byte[keyLen];
        byte[] value = new byte[(int) valLen];
        buf.position(HEADER_SIZE);
        buf.get(key);
        buf.get(value);

        return new LogRecord(seq, tstamp, RecordType.fromCode(typeCode), key, value);
    }

    /**
     * CRC32C over bytes {@code [4, end)}.
     *
     * <p>The length fields are deliberately inside this range. If they were not,
     * a flipped bit in {@code keyLen} would go unnoticed, the reader would consume
     * the wrong number of bytes, and every record boundary after it would be lost.
     */
    private static int checksumOf(ByteBuffer buf, int end) {
        ByteBuffer view = buf.duplicate();
        view.limit(end);
        view.position(CRC_COVERAGE_START);

        CRC32C crc = new CRC32C();
        crc.update(view);
        return (int) crc.getValue();
    }
}
