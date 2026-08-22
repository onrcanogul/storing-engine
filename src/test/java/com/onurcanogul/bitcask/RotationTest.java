package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.store.SegmentFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationTest {

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Small values plus a small segment, so rotation happens quickly. */
    private BitcaskConfig rotatingConfig(int segmentSize) {
        return BitcaskConfig.defaults()
                .withMaxValueSize(64)
                .withMaxSegmentSize(segmentSize);
    }

    private List<Integer> segments() throws Exception {
        return SegmentFiles.listFileIds(dir);
    }

    @Test
    void aFreshStoreStartsWithOneSegment() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertEquals(List.of(1), segments());
            assertEquals(8, Files.size(SegmentFiles.pathOf(dir, 1)));
        }
    }

    @Test
    void writingPastTheThresholdOpensANewSegment() throws Exception {
        // Each record is 27 + 2 + 2 = 31 bytes. After the 8-byte header, six of
        // them reach 194 bytes; the seventh would pass 200.
        try (Bitcask db = Bitcask.open(dir, rotatingConfig(200))) {
            for (int i = 0; i < 6; i++) {
                db.put(b("k" + i), b("v" + i));
            }
            assertEquals(List.of(1), segments(), "six records should still fit");

            db.put(b("k6"), b("v6"));
            assertEquals(List.of(1, 2), segments(), "the seventh record should have rotated");
        }
    }

    @Test
    void noSegmentEverExceedsTheThreshold() throws Exception {
        try (Bitcask db = Bitcask.open(dir, rotatingConfig(300))) {
            for (int i = 0; i < 200; i++) {
                db.put(b("key:" + i), b("value:" + i));
            }
        }
        for (int fileId : segments()) {
            long size = Files.size(SegmentFiles.pathOf(dir, fileId));
            assertTrue(size <= 300, "segment " + fileId + " grew to " + size + " bytes");
        }
    }

    @Test
    void valuesRemainReadableFromOlderSegments() throws Exception {
        try (Bitcask db = Bitcask.open(dir, rotatingConfig(300))) {
            for (int i = 0; i < 100; i++) {
                db.put(b("key:" + i), b("value:" + i));
            }
            assertTrue(segments().size() > 5, "the test needs several segments to be meaningful");

            // The earliest keys live in segments that are long since closed.
            for (int i = 0; i < 100; i++) {
                assertArrayEquals(b("value:" + i), db.get(b("key:" + i)), "key:" + i);
            }
        }
    }

    @Test
    void everythingSurvivesAReopenAcrossManySegments() throws Exception {
        try (Bitcask db = Bitcask.open(dir, rotatingConfig(300))) {
            for (int i = 0; i < 100; i++) {
                db.put(b("key:" + i), b("value:" + i));
            }
        }
        int segmentCount = segments().size();

        try (Bitcask db = Bitcask.open(dir, rotatingConfig(300))) {
            assertEquals(100, db.size());
            for (int i = 0; i < 100; i++) {
                assertArrayEquals(b("value:" + i), db.get(b("key:" + i)), "key:" + i);
            }
            assertEquals(segmentCount, segments().size(), "reopening must not create a segment");
        }
    }

    @Test
    void writingResumesInTheActiveSegmentAfterAReopen() throws Exception {
        try (Bitcask db = Bitcask.open(dir, rotatingConfig(300))) {
            for (int i = 0; i < 50; i++) {
                db.put(b("key:" + i), b("value:" + i));
            }
        }
        try (Bitcask db = Bitcask.open(dir, rotatingConfig(300))) {
            db.put(b("after"), b("reopen"));
        }
        try (Bitcask db = Bitcask.open(dir, rotatingConfig(300))) {
            assertArrayEquals(b("reopen"), db.get(b("after")));
            assertEquals(51, db.size());
        }
    }

    @Test
    void overwritesAndDeletesWorkAcrossSegments() throws Exception {
        try (Bitcask db = Bitcask.open(dir, rotatingConfig(300))) {
            db.put(b("k"), b("first"));

            for (int i = 0; i < 60; i++) {          // push k far into an old segment
                db.put(b("filler:" + i), b("x"));
            }
            db.put(b("k"), b("second"));            // new version in a newer segment

            assertArrayEquals(b("second"), db.get(b("k")));

            db.delete(b("k"));
            assertEquals(null, db.get(b("k")));
        }
        try (Bitcask db = Bitcask.open(dir, rotatingConfig(300))) {
            assertEquals(null, db.get(b("k")), "the delete must outlive the reopen");
        }
    }

    @Test
    void aLegacySingleFileLogIsAdoptedOnOpen() throws Exception {
        // Simulate a phase-one store: write, then rename to the old name.
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("old"), b("data"));
        }
        Files.move(SegmentFiles.pathOf(dir, 1), dir.resolve(SegmentFiles.LEGACY_LOG_NAME));

        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertArrayEquals(b("data"), db.get(b("old")));
            assertEquals(List.of(1), segments());
        }
    }

    @Test
    void anEmptyTrailingSegmentFromAnInterruptedRotationIsRepaired() throws Exception {
        try (Bitcask db = Bitcask.open(dir, rotatingConfig(200))) {
            db.put(b("a"), b("1"));
        }
        // A rotation that created the file but died before writing its header.
        Files.createFile(SegmentFiles.pathOf(dir, 2));

        try (Bitcask db = Bitcask.open(dir, rotatingConfig(200))) {
            assertArrayEquals(b("1"), db.get(b("a")));
            db.put(b("b"), b("2"));
            assertArrayEquals(b("2"), db.get(b("b")));
        }
    }
}
