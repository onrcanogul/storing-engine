package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BitcaskReadTest {

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void getReturnsWhatPutStored() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("hello"));
            assertArrayEquals(b("hello"), db.get(b("k")));
        }
    }

    @Test
    void keysAreComparedByContentNotByReference() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("user:42"), b("v"));

            byte[] lookalike = b("user:42");
            assertNotSame(lookalike, b("user:42"));
            assertArrayEquals(b("v"), db.get(lookalike));
        }
    }

    @Test
    void missingKeyReturnsNull() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertNull(db.get(b("nope")));
        }
    }

    @Test
    void latestWriteWins() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("v1"));
            db.put(b("k"), b("v2"));
            db.put(b("k"), b("v3"));
            assertArrayEquals(b("v3"), db.get(b("k")));
        }
    }

    @Test
    void emptyValueRoundTrips() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), new byte[0]);
            assertArrayEquals(new byte[0], db.get(b("k")));
        }
    }

    @Test
    void returnedArrayBelongsToTheCaller() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("value"));

            byte[] first = db.get(b("k"));
            first[0] = 'X';

            assertArrayEquals(b("value"), db.get(b("k")));
        }
    }

    @Test
    void mutatingTheCallersKeyAfterPutDoesNotOrphanTheEntry() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            byte[] key = b("stable");
            db.put(key, b("v"));

            key[0] = 'X';   // caller reuses its buffer

            assertNotNull(db.get(b("stable")));
            assertNull(db.get(b("Xtable")));
        }
    }

    @Test
    void largeValueRoundTrips() throws Exception {
        byte[] big = new byte[300_000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31);
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("big"), big);
            assertArrayEquals(big, db.get(b("big")));
        }
    }

    @Test
    void manyKeysAreAllReadableBack() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            for (int i = 0; i < 1_000; i++) {
                db.put(b("key:" + i), b("value:" + i));
            }
            for (int i = 0; i < 1_000; i++) {
                assertArrayEquals(b("value:" + i), db.get(b("key:" + i)));
            }
        }
    }

    @Test
    void getOnAClosedEngineThrows() throws Exception {
        Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults());
        db.close();

        assertThrows(IllegalStateException.class, () -> db.get(b("k")));
    }
}
