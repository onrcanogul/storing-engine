package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.store.SegmentFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitcaskDeleteTest {

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private long logSize() throws Exception {
        return Files.size(SegmentFiles.pathOf(dir, 1));
    }

    @Test
    void deleteRemovesTheKey() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("v"));

            assertTrue(db.delete(b("k")));
            assertNull(db.get(b("k")));
            assertEquals(0, db.size());
        }
    }

    @Test
    void deletingAMissingKeyReturnsFalseAndWritesNothing() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            long before = logSize();

            assertFalse(db.delete(b("nope")));
            assertEquals(before, logSize());
        }
    }

    @Test
    void deleteAppendsATombstoneWithNoValue() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("v"));
            long before = logSize();

            db.delete(b("k"));

            assertEquals(before + 27 + 1, logSize());
        }
    }

    @Test
    void deletingTwiceReturnsFalseTheSecondTime() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("v"));

            assertTrue(db.delete(b("k")));
            long afterFirst = logSize();

            assertFalse(db.delete(b("k")));
            assertEquals(afterFirst, logSize());
        }
    }

    @Test
    void aKeyCanBeWrittenAgainAfterDeletion() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("v1"));
            db.delete(b("k"));
            db.put(b("k"), b("v2"));

            assertArrayEquals(b("v2"), db.get(b("k")));
            assertEquals(1, db.size());
        }
    }

    @Test
    void deletingOneKeyLeavesTheOthersAlone() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("a"), b("1"));
            db.put(b("b"), b("2"));
            db.put(b("c"), b("3"));

            db.delete(b("b"));

            assertArrayEquals(b("1"), db.get(b("a")));
            assertNull(db.get(b("b")));
            assertArrayEquals(b("3"), db.get(b("c")));
            assertEquals(2, db.size());
        }
    }

    @Test
    void emptyKeyIsRejected() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertThrows(IllegalArgumentException.class, () -> db.delete(new byte[0]));
        }
    }

    @Test
    void deleteOnAClosedEngineThrows() throws Exception {
        Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults());
        db.close();

        assertThrows(IllegalStateException.class, () -> db.delete(b("k")));
    }
}
