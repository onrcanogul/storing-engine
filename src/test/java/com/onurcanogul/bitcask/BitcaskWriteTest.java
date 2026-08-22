package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.recovery.RecoveryMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BitcaskWriteTest {

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private long logSize() throws Exception {
        return Files.size(dir.resolve("data.log"));
    }

    @Test
    void putAppendsExactlyOneRecord() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            long before = logSize();
            db.put(b("k"), b("v"));

            assertEquals(before + 27 + 1 + 1, logSize());
            assertEquals(1, db.size());
        }
    }

    @Test
    void overwritingAKeyAppendsButKeepsOneIndexEntry() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("v1"));
            long afterFirst = logSize();
            db.put(b("k"), b("v2"));

            assertEquals(afterFirst + 27 + 1 + 2, logSize());
            assertEquals(1, db.size());
        }
    }

    @Test
    void manyKeysAreAllIndexed() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            for (int i = 0; i < 500; i++) {
                db.put(b("key:" + i), b("value:" + i));
            }
            assertEquals(500, db.size());
        }
    }

    @Test
    void emptyKeyIsRejected() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertThrows(IllegalArgumentException.class, () -> db.put(new byte[0], b("v")));
        }
    }

    @Test
    void nullKeyIsRejected() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertThrows(IllegalArgumentException.class, () -> db.put(null, b("v")));
        }
    }

    @Test
    void keyAboveTheFormatLimitIsRejected() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertThrows(IllegalArgumentException.class, () -> db.put(new byte[65_536], b("v")));
        }
    }

    @Test
    void valueAboveTheConfiguredLimitIsRejected() throws Exception {
        BitcaskConfig small = new BitcaskConfig(1024, SyncPolicy.NEVER, RecoveryMode.TOLERATE_TAIL);
        try (Bitcask db = Bitcask.open(dir, small)) {
            assertThrows(IllegalArgumentException.class, () -> db.put(b("k"), new byte[2048]));
        }
    }

    @Test
    void aRejectedPutWritesNothing() throws Exception {
        BitcaskConfig small = new BitcaskConfig(1024, SyncPolicy.NEVER, RecoveryMode.TOLERATE_TAIL);
        try (Bitcask db = Bitcask.open(dir, small)) {
            long before = logSize();
            assertThrows(IllegalArgumentException.class, () -> db.put(b("k"), new byte[2048]));

            assertEquals(before, logSize());
            assertEquals(0, db.size());
        }
    }

    @Test
    void nullValueIsStoredAsEmpty() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), null);

            assertEquals(1, db.size());
            assertEquals(8 + 27 + 1, logSize());
        }
    }

    @Test
    void alwaysSyncPolicyStillWrites() throws Exception {
        BitcaskConfig sync = new BitcaskConfig(
                BitcaskConfig.DEFAULT_MAX_VALUE_SIZE, SyncPolicy.ALWAYS, RecoveryMode.TOLERATE_TAIL);
        try (Bitcask db = Bitcask.open(dir, sync)) {
            db.put(b("k"), b("v"));

            assertEquals(1, db.size());
            assertEquals(8 + 27 + 1 + 1, logSize());
        }
    }

    @Test
    void putOnAClosedEngineThrows() throws Exception {
        Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults());
        db.close();

        assertThrows(IllegalStateException.class, () -> db.put(b("k"), b("v")));
    }
}
