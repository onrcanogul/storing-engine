package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.compaction.MergeReport;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeTest {

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Small segments so a handful of writes produces several of them. */
    private BitcaskConfig config() {
        return BitcaskConfig.defaults()
                .withMaxValueSize(64)
                .withMaxSegmentSize(300);
    }

    private long diskUsage() throws Exception {
        long total = 0;
        for (int fileId : SegmentFiles.listFileIds(dir)) {
            total += Files.size(SegmentFiles.pathOf(dir, fileId));
        }
        return total;
    }

    @Test
    void aCleanStoreHasNothingToMerge() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int i = 0; i < 20; i++) {
                db.put(b("key:" + i), b("v"));
            }
            MergeReport report = db.merge();

            assertFalse(report.didAnything());
            assertEquals(0, report.recordsMoved());
        }
    }

    @Test
    void mergeReclaimsSpaceFromOverwrittenRecords() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int round = 0; round < 30; round++) {
                for (int k = 0; k < 5; k++) {
                    db.put(b("key:" + k), b("round:" + round));
                }
            }
            long before = diskUsage();
            assertTrue(db.compactionStats().deadRatio() > 0.8, "the test needs a lot of garbage");

            MergeReport report = db.merge();

            assertTrue(report.didAnything());
            assertTrue(diskUsage() < before / 2,
                    "disk went from " + before + " to " + diskUsage());
            assertTrue(report.bytesReclaimed() > 0);
        }
    }

    @Test
    void everyLiveValueSurvivesAMerge() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int round = 0; round < 20; round++) {
                for (int k = 0; k < 10; k++) {
                    db.put(b("key:" + k), b("round:" + round + ":key:" + k));
                }
            }
            db.merge();

            assertEquals(10, db.size());
            for (int k = 0; k < 10; k++) {
                assertArrayEquals(b("round:19:key:" + k), db.get(b("key:" + k)), "key:" + k);
            }
        }
    }

    @Test
    void mergedStateSurvivesAReopen() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int round = 0; round < 20; round++) {
                for (int k = 0; k < 10; k++) {
                    db.put(b("key:" + k), b("round:" + round + ":key:" + k));
                }
            }
            db.merge();
        }
        try (Bitcask db = Bitcask.open(dir, config())) {
            assertEquals(10, db.size());
            for (int k = 0; k < 10; k++) {
                assertArrayEquals(b("round:19:key:" + k), db.get(b("key:" + k)));
            }
            assertEquals(0, db.recoveryReport().bytesDiscarded());
        }
    }

    @Test
    void aDeletedKeyStaysDeletedThroughAMerge() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            db.put(b("gone"), b("value"));
            for (int i = 0; i < 30; i++) {           // push it into a closed segment
                db.put(b("filler:" + i), b("x"));
                db.put(b("filler:" + i), b("y"));    // and make those segments dirty
            }
            db.delete(b("gone"));

            db.merge();
            assertNull(db.get(b("gone")));
        }
        try (Bitcask db = Bitcask.open(dir, config())) {
            assertNull(db.get(b("gone")), "the delete must outlive both the merge and the reopen");
        }
    }

    @Test
    void theActiveSegmentIsNeverRemoved() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int round = 0; round < 20; round++) {
                for (int k = 0; k < 5; k++) {
                    db.put(b("key:" + k), b("round:" + round));
                }
            }
            int activeBefore = SegmentFiles.activeFileId(dir).orElseThrow();
            MergeReport report = db.merge();

            assertFalse(report.mergedSegments().contains(activeBefore),
                    "the active segment must not be merged");
        }
    }

    @Test
    void mergingTwiceInARowIsHarmless() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int round = 0; round < 20; round++) {
                for (int k = 0; k < 5; k++) {
                    db.put(b("key:" + k), b("round:" + round));
                }
            }
            db.merge();
            MergeReport second = db.merge();

            assertFalse(second.didAnything(), "there should be nothing left to reclaim");
            assertEquals(5, db.size());
        }
    }

    @Test
    void writingContinuesNormallyAfterAMerge() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int round = 0; round < 20; round++) {
                for (int k = 0; k < 5; k++) {
                    db.put(b("key:" + k), b("round:" + round));
                }
            }
            db.merge();

            db.put(b("after"), b("merge"));
            assertArrayEquals(b("merge"), db.get(b("after")));
            assertArrayEquals(b("round:19"), db.get(b("key:0")));
        }
    }

    @Test
    void aLiveRecordInsideADirtySegmentIsCarriedForward() throws Exception {
        // The demo case where everything live happens to sit in clean segments
        // never exercises the copy path. Here a survivor is stranded among
        // garbage, so the merge has to move it rather than just delete around it.
        try (Bitcask db = Bitcask.open(dir, config())) {
            db.put(b("survivor"), b("original"));

            // Fill the same segment with records that will die, then kill them.
            for (int i = 0; i < 6; i++) {
                db.put(b("doomed:" + i), b("v1"));
            }
            for (int i = 0; i < 6; i++) {
                db.put(b("doomed:" + i), b("v2"));
            }

            int survivorSegment = db.compactionStats().segments().stream()
                    .filter(segment -> !segment.active() && segment.deadBytes() > 0)
                    .findFirst()
                    .orElseThrow()
                    .fileId();
            assertEquals(1, survivorSegment, "the survivor should be in the first, now-dirty segment");

            MergeReport report = db.merge();

            assertTrue(report.recordsMoved() > 0,
                    "a live record inside a merged segment must be copied forward");
            assertArrayEquals(b("original"), db.get(b("survivor")));
        }
        try (Bitcask db = Bitcask.open(dir, config())) {
            assertArrayEquals(b("original"), db.get(b("survivor")),
                    "the carried-forward record must survive a reopen");
        }
    }

    @Test
    void aCarriedForwardRecordIsNotOverwrittenByAConcurrentUpdate() throws Exception {
        // A merge must never resurrect a value. Rewriting the key just before the
        // merge runs means the old copy is stale, and copying it would undo the
        // newer write.
        try (Bitcask db = Bitcask.open(dir, config())) {
            db.put(b("k"), b("old"));

            for (int i = 0; i < 6; i++) {
                db.put(b("doomed:" + i), b("v1"));
            }
            for (int i = 0; i < 6; i++) {
                db.put(b("doomed:" + i), b("v2"));
            }

            db.put(b("k"), b("new"));   // the copy in segment 1 is now stale
            db.merge();

            assertArrayEquals(b("new"), db.get(b("k")));
        }
        try (Bitcask db = Bitcask.open(dir, config())) {
            assertArrayEquals(b("new"), db.get(b("k")));
        }
    }

    @Test
    void mergeCountsWhatItMovedAndDiscarded() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int round = 0; round < 10; round++) {
                for (int k = 0; k < 5; k++) {
                    db.put(b("key:" + k), b("round:" + round));
                }
            }
            MergeReport report = db.merge();

            assertTrue(report.recordsDiscarded() > report.recordsMoved(),
                    "most records in a heavily overwritten store are garbage: "
                            + report.recordsMoved() + " moved, " + report.recordsDiscarded() + " discarded");
        }
    }
}
