package com.onurcanogul.bitcask.format;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecordCodecTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void headerIsExactly27Bytes() {
        assertEquals(27, RecordCodec.HEADER_SIZE);
    }

    @Test
    void roundTripPreservesEverything() throws Exception {
        ByteBuffer buf = RecordCodec.encode(7L, 1234L, RecordType.PUT, bytes("user:42"), bytes("hello"));
        LogRecord record = RecordCodec.decode(buf);

        assertEquals(7L, record.seq());
        assertEquals(1234L, record.tstamp());
        assertEquals(RecordType.PUT, record.type());
        assertArrayEquals(bytes("user:42"), record.key());
        assertArrayEquals(bytes("hello"), record.value());
    }

    @Test
    void encodedSizeIsHeaderPlusPayload() {
        ByteBuffer buf = RecordCodec.encode(1L, 1L, RecordType.PUT, bytes("ab"), bytes("xyz"));
        assertEquals(27 + 2 + 3, buf.remaining());
        assertEquals(27 + 2 + 3, RecordCodec.recordSize(2, 3));
    }

    @Test
    void emptyValueIsValid() throws Exception {
        ByteBuffer buf = RecordCodec.encode(1L, 1L, RecordType.PUT, bytes("k"), new byte[0]);
        LogRecord record = RecordCodec.decode(buf);

        assertEquals(0, record.value().length);
        assertEquals(RecordType.PUT, record.type());
    }

    @Test
    void tombstoneRoundTrips() throws Exception {
        ByteBuffer buf = RecordCodec.encode(9L, 1L, RecordType.TOMBSTONE, bytes("k"), new byte[0]);
        assertEquals(RecordType.TOMBSTONE, RecordCodec.decode(buf).type());
    }

    @Test
    void flippedBitInValueIsDetected() {
        ByteBuffer buf = RecordCodec.encode(1L, 1L, RecordType.PUT, bytes("k"), bytes("value"));
        int last = buf.limit() - 1;
        buf.put(last, (byte) (buf.get(last) ^ 0x01));

        assertThrows(CorruptRecordException.class, () -> RecordCodec.decode(buf));
    }

    @Test
    void flippedBitInKeyLengthIsDetected() {
        // keyLen sits at offset 21. It must be under the CRC: a reader that trusted a
        // corrupt length would lose every record boundary after this point.
        ByteBuffer buf = RecordCodec.encode(1L, 1L, RecordType.PUT, bytes("key"), bytes("value"));
        buf.put(22, (byte) (buf.get(22) ^ 0x01));

        assertThrows(CorruptRecordException.class, () -> RecordCodec.decode(buf));
    }

    @Test
    void flippedBitInSequenceNumberIsDetected() {
        ByteBuffer buf = RecordCodec.encode(1L, 1L, RecordType.PUT, bytes("k"), bytes("v"));
        buf.put(11, (byte) (buf.get(11) ^ 0x01));

        assertThrows(CorruptRecordException.class, () -> RecordCodec.decode(buf));
    }

    @Test
    void keyLongerThan32kRoundTripsCorrectly() throws Exception {
        // Guards the signed/unsigned bug: a plain readShort() returns a negative
        // number for any length above 32767.
        byte[] bigKey = new byte[40_000];
        for (int i = 0; i < bigKey.length; i++) {
            bigKey[i] = (byte) i;
        }

        ByteBuffer buf = RecordCodec.encode(1L, 1L, RecordType.PUT, bigKey, bytes("v"));
        assertArrayEquals(bigKey, RecordCodec.decode(buf).key());
    }

    @Test
    void emptyKeyIsRejectedOnEncode() {
        assertThrows(IllegalArgumentException.class,
                () -> RecordCodec.encode(1L, 1L, RecordType.PUT, new byte[0], bytes("v")));
    }

    @Test
    void keyAboveMaxIsRejectedOnEncode() {
        assertThrows(IllegalArgumentException.class,
                () -> RecordCodec.encode(1L, 1L, RecordType.PUT, new byte[65_536], bytes("v")));
    }

    @Test
    void headerValidationRejectsZeroKeyLength() {
        assertThrows(CorruptRecordException.class,
                () -> RecordCodec.validateHeaderFields((byte) 1, 0, 10, 1_000));
    }

    @Test
    void headerValidationRejectsUnknownType() {
        assertThrows(CorruptRecordException.class,
                () -> RecordCodec.validateHeaderFields((byte) 9, 5, 10, 1_000));
    }

    @Test
    void headerValidationRejectsRecordLongerThanRemainingFile() {
        assertThrows(CorruptRecordException.class,
                () -> RecordCodec.validateHeaderFields((byte) 1, 5, 10_000, 100));
    }

    @Test
    void headerValidationRejectsValueAboveHardLimit() {
        assertThrows(CorruptRecordException.class,
                () -> RecordCodec.validateHeaderFields((byte) 1, 5, 65L * 1024 * 1024, Long.MAX_VALUE));
    }

    @Test
    void headerValidationAcceptsAPlausibleRecord() throws Exception {
        RecordCodec.validateHeaderFields((byte) 1, 5, 10, 1_000);
    }
}
