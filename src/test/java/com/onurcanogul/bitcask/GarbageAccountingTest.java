package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.compaction.CompactionStats;
import com.onurcanogul.bitcask.compaction.SegmentStats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GarbageAccountingTest {

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** A record for a one-byte key and one-byte value. */
    private static final int SMALL_RECORD = 27 + 1 + 1;

    @Test
    void aFreshStoreCarriesNoGarbage() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertEquals(0, db.compactionStats().deadBytes());
            assertEquals(0.0, db.compactionStats().deadRatio(), 1e-9);
        }
    }

    @Test
    void writingDistinctKeysCreatesNoGarbage() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            for (int i = 0; i < 20; i++) {
                db.put(b("key:" + i), b("v"));
            }
            assertEquals(0, db.compactionStats().deadBytes());
        }
    }

    @Test
    void overwritingAKeyKillsTheOlderRecord() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("1"));
            assertEquals(0, db.compactionStats().deadBytes());

            db.put(b("k"), b("2"));
            assertEquals(SMALL_RECORD, db.compactionStats().deadBytes());

            db.put(b("k"), b("3"));
            assertEquals(2 * SMALL_RECORD, db.compactionStats().deadBytes());
        }
    }

    @Test
    void deletingAKeyKillsTheRecordItCancels() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("1"));
            db.delete(b("k"));

            // The PUT is dead. The tombstone is not counted: it still has work to
            // do until compaction can prove otherwise.
            assertEquals(SMALL_RECORD, db.compactionStats().deadBytes());
        }
    }

    @Test
    void deletingAnAbsentKeyChangesNothing() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.delete(b("nope"));
            assertEquals(0, db.compactionStats().deadBytes());
        }
    }

    @Test
    void garbageIsAttributedToTheSegmentThatHoldsIt() throws Exception {
        BitcaskConfig config = BitcaskConfig.defaults()
                .withMaxValueSize(64)
                .withMaxSegmentSize(200);

        try (Bitcask db = Bitcask.open(dir, config)) {
            db.put(b("k"), b("1"));                 // lands in segment 1

            for (int i = 0; i < 20; i++) {          // push into later segments
                db.put(b("f" + i), b("x"));
            }
            db.put(b("k"), b("2"));                 // kills the record in segment 1

            SegmentStats first = db.compactionStats().segments().stream()
                    .filter(s -> s.fileId() == 1)
                    .findFirst()
                    .orElseThrow();

            assertEquals(SMALL_RECORD, first.deadBytes(), "the garbage belongs to segment 1");
            assertTrue(first.deadRatio() > 0);
        }
    }

    @Test
    void garbageCountsSurviveAReopen() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("1"));
            db.put(b("k"), b("2"));
            db.put(b("k"), b("3"));
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            // Replay must rebuild the same accounting, not start from zero.
            assertEquals(2 * SMALL_RECORD, db.compactionStats().deadBytes());
        }
    }

    @Test
    void theActiveSegmentIsMarkedAsSuch() throws Exception {
        BitcaskConfig config = BitcaskConfig.defaults()
                .withMaxValueSize(64)
                .withMaxSegmentSize(200);

        try (Bitcask db = Bitcask.open(dir, config)) {
            for (int i = 0; i < 20; i++) {
                db.put(b("k" + i), b("v"));
            }
            CompactionStats stats = db.compactionStats();

            long activeCount = stats.segments().stream().filter(SegmentStats::active).count();
            assertEquals(1, activeCount, "exactly one segment is active");

            int highest = stats.segments().stream().mapToInt(SegmentStats::fileId).max().orElseThrow();
            assertTrue(stats.segments().stream()
                            .filter(SegmentStats::active)
                            .allMatch(s -> s.fileId() == highest),
                    "the active segment is the highest-numbered one");
        }
    }

    @Test
    void statsReportEverySegmentWithItsRealSize() throws Exception {
        BitcaskConfig config = BitcaskConfig.defaults()
                .withMaxValueSize(64)
                .withMaxSegmentSize(200);

        try (Bitcask db = Bitcask.open(dir, config)) {
            for (int i = 0; i < 30; i++) {
                db.put(b("k" + i), b("v"));
            }
            CompactionStats stats = db.compactionStats();

            assertEquals(db.segmentCount(), stats.segments().size());
            assertTrue(stats.totalBytes() > 0);
            for (SegmentStats segment : stats.segments()) {
                assertTrue(segment.totalBytes() >= 8, "every segment has at least a header");
            }
        }
    }
}
