package com.onurcanogul.bitcask.recovery;

import com.onurcanogul.bitcask.Bitcask;
import com.onurcanogul.bitcask.BitcaskConfig;
import com.onurcanogul.bitcask.SyncPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryTest {

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Path log() {
        return dir.resolve("data.log");
    }

    private BitcaskConfig strict() {
        return new BitcaskConfig(
                BitcaskConfig.DEFAULT_MAX_VALUE_SIZE, SyncPolicy.NEVER, RecoveryMode.STRICT);
    }

    /** Flips one bit at the given offset, simulating bit rot. */
    private void flipBitAt(long offset) throws IOException {
        try (FileChannel channel = FileChannel.open(log(),
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            channel.read(one, offset);
            byte flipped = (byte) (one.get(0) ^ 0x01);
            channel.write(ByteBuffer.wrap(new byte[] {flipped}), offset);
        }
    }

    @Test
    void dataSurvivesAReopen() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("a"), b("1"));
            db.put(b("b"), b("2"));
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertArrayEquals(b("1"), db.get(b("a")));
            assertArrayEquals(b("2"), db.get(b("b")));
            assertEquals(2, db.size());

            RecoveryReport report = db.recoveryReport();
            assertEquals(2, report.recordsReplayed());
            assertEquals(2, report.liveKeys());
            assertEquals(0, report.bytesDiscarded());
            assertEquals(StopReason.CLEAN_EOF, report.reason());
        }
    }

    @Test
    void theLatestWriteWinsAfterAReopen() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("old"));
            db.put(b("k"), b("new"));
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertArrayEquals(b("new"), db.get(b("k")));
            assertEquals(1, db.size());
            assertEquals(2, db.recoveryReport().recordsReplayed());
        }
    }

    @Test
    void aDeletedKeyStaysDeletedAfterAReopen() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("v"));
            db.delete(b("k"));
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertNull(db.get(b("k")));
            assertEquals(0, db.size());
        }
    }

    @Test
    void aKeyWrittenAgainAfterDeletionSurvives() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("v1"));
            db.delete(b("k"));
            db.put(b("k"), b("v2"));
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertArrayEquals(b("v2"), db.get(b("k")));
        }
    }

    @Test
    void writingContinuesCorrectlyAfterAReopen() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("a"), b("1"));
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("b"), b("2"));
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("c"), b("3"));
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertArrayEquals(b("1"), db.get(b("a")));
            assertArrayEquals(b("2"), db.get(b("b")));
            assertArrayEquals(b("3"), db.get(b("c")));
            assertEquals(3, db.size());
        }
    }

    @Test
    void aTruncatedTailIsDiscardedAndReported() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("a"), b("1"));
            db.put(b("b"), b("2"));
        }
        long full = Files.size(log());
        try (FileChannel channel = FileChannel.open(log(), StandardOpenOption.WRITE)) {
            channel.truncate(full - 3);   // a write interrupted mid-record
        }

        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertArrayEquals(b("1"), db.get(b("a")));
            assertNull(db.get(b("b")));

            RecoveryReport report = db.recoveryReport();
            assertTrue(report.lostData());
            assertEquals(1, report.recordsReplayed());
            assertEquals(StopReason.SHORT_READ, report.reason());
        }
    }

    @Test
    void garbageAppendedToTheTailIsTruncatedSoWritingResumesCleanly() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("a"), b("1"));
        }
        try (FileChannel channel = FileChannel.open(log(),
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5}));
        }

        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertTrue(db.recoveryReport().lostData());
            db.put(b("b"), b("2"));
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertArrayEquals(b("1"), db.get(b("a")));
            assertArrayEquals(b("2"), db.get(b("b")));
            assertEquals(2, db.size());
            assertEquals(0, db.recoveryReport().bytesDiscarded());
        }
    }

    @Test
    void corruptionInTheMiddleDiscardsEverythingAfterIt() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("a"), b("1"));
            db.put(b("b"), b("2"));
            db.put(b("c"), b("3"));
        }
        // Corrupt the value byte of the second record: header(8) + record(29) + 28
        flipBitAt(8 + 29 + 28);

        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertArrayEquals(b("1"), db.get(b("a")));
            assertNull(db.get(b("b")));
            assertNull(db.get(b("c")));   // sound, but unreachable past the damage

            RecoveryReport report = db.recoveryReport();
            assertEquals(1, report.recordsReplayed());
            assertEquals(StopReason.CRC_MISMATCH, report.reason());
            assertTrue(report.lostData());
        }
    }

    @Test
    void strictModeRefusesToOpenADamagedLog() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("a"), b("1"));
        }
        try (FileChannel channel = FileChannel.open(log(), StandardOpenOption.WRITE)) {
            channel.truncate(Files.size(log()) - 2);
        }

        assertThrows(IOException.class, () -> Bitcask.open(dir, strict()));
    }

    @Test
    void strictModeDoesNotTruncateTheDamagedLog() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("a"), b("1"));
            db.put(b("b"), b("2"));
        }
        long damaged = Files.size(log()) - 3;
        try (FileChannel channel = FileChannel.open(log(), StandardOpenOption.WRITE)) {
            channel.truncate(damaged);
        }

        assertThrows(IOException.class, () -> Bitcask.open(dir, strict()));

        // A refusal must leave the evidence intact for whoever investigates.
        assertEquals(damaged, Files.size(log()));
    }

    @Test
    void strictModeOpensACleanLogNormally() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("a"), b("1"));
        }
        try (Bitcask db = Bitcask.open(dir, strict())) {
            assertArrayEquals(b("1"), db.get(b("a")));
        }
    }

    @Test
    void aCorruptedRecordIsAlsoCaughtOnRead() throws Exception {
        // Corruption inside the last record: recovery stops before it, so the
        // key is absent rather than readable-but-wrong.
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("a"), b("1"));
            db.put(b("k"), b("value"));
        }
        flipBitAt(Files.size(log()) - 1);

        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertNotNull(db.get(b("a")));
            assertNull(db.get(b("k")));
            assertEquals(StopReason.CRC_MISMATCH, db.recoveryReport().reason());
        }
    }

    @Test
    void anEmptyLogRecoversToAnEmptyIndex() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertEquals(0, db.size());
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertEquals(0, db.size());
            assertEquals(0, db.recoveryReport().recordsReplayed());
            assertEquals(StopReason.CLEAN_EOF, db.recoveryReport().reason());
        }
    }
}
