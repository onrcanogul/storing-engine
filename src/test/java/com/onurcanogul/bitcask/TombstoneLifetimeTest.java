package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.compaction.MergeReport;
import com.onurcanogul.bitcask.compaction.SegmentStats;
import com.onurcanogul.bitcask.format.FileHeader;
import com.onurcanogul.bitcask.format.RecordCodec;
import com.onurcanogul.bitcask.format.RecordType;
import com.onurcanogul.bitcask.store.SegmentFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tombstone exists only to cancel an older PUT. Once no older PUT can
 * survive the merge, keeping the tombstone is pure waste — but dropping one
 * too early resurrects a deleted key, so the rule has to be exact.
 */
class TombstoneLifetimeTest {

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private BitcaskConfig config() {
        return BitcaskConfig.defaults()
                .withMaxValueSize(64)
                .withMaxSegmentSize(300);
    }

    /** Walks every segment on disk and counts the tombstones actually stored. */
    private int tombstonesOnDisk() throws Exception {
        int count = 0;
        for (int fileId : SegmentFiles.listFileIds(dir)) {
            byte[] bytes = Files.readAllBytes(SegmentFiles.pathOf(dir, fileId));
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            int pos = FileHeader.SIZE;
            while (pos + RecordCodec.HEADER_SIZE <= bytes.length) {
                byte type = bytes[pos + 20];
                int keyLen = buf.getShort(pos + 21) & 0xFFFF;
                int valLen = buf.getInt(pos + 23);
                if (type == RecordType.TOMBSTONE.code()) {
                    count++;
                }
                pos += RecordCodec.HEADER_SIZE + keyLen + valLen;
            }
        }
        return count;
    }

    private int closedSegments(Bitcask db) throws Exception {
        return (int) db.compactionStats().segments().stream()
                .filter(s -> !s.active())
                .count();
    }

    @Test
    void aTombstoneIsDroppedWhenNoOlderSegmentSurvivesTheMerge() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            // Enough overwriting that every closed segment is mostly garbage.
            for (int round = 0; round < 8; round++) {
                for (int k = 0; k < 5; k++) {
                    db.put(b("key:" + k), b("round:" + round));
                }
            }
            db.delete(b("key:0"));
            for (int round = 8; round < 20; round++) {
                for (int k = 1; k < 5; k++) {
                    db.put(b("key:" + k), b("round:" + round));
                }
            }
            assertTrue(tombstonesOnDisk() > 0, "the delete should be on disk before the merge");

            int closedBefore = closedSegments(db);
            MergeReport report = db.merge();

            // The whole point of this test: nothing older is left behind.
            assertEquals(closedBefore, report.mergedSegments().size(),
                    "this test needs every closed segment to be a merge candidate");
            assertEquals(0, tombstonesOnDisk(),
                    "with no older segment left, the tombstone cancels nothing and should be gone");
            assertNull(db.get(b("key:0")));
        }
        try (Bitcask db = Bitcask.open(dir, config())) {
            assertNull(db.get(b("key:0")), "the deleted key must not come back after a restart");
        }
    }

    @Test
    void aTombstoneIsKeptWhileAnOlderSegmentIsStillUnmerged() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            // Segment 1 stays clean, so the merge will not touch it — and it holds
            // the PUT that the tombstone has to keep cancelling.
            db.put(b("k"), b("v"));
            for (int i = 0; i < 6; i++) {
                db.put(b("keep:" + i), b("v"));
            }

            for (int round = 0; round < 6; round++) {
                for (int j = 0; j < 5; j++) {
                    db.put(b("junk:" + j), b("round:" + round));
                }
            }
            db.delete(b("k"));
            for (int round = 6; round < 20; round++) {
                for (int j = 0; j < 5; j++) {
                    db.put(b("junk:" + j), b("round:" + round));
                }
            }

            int closedBefore = closedSegments(db);
            MergeReport report = db.merge();

            assertTrue(report.mergedSegments().size() < closedBefore,
                    "this test needs a partial merge, with an older segment surviving");
            assertTrue(report.mergedSegments().stream().mapToInt(Integer::intValue).min().orElseThrow()
                            > SegmentFiles.FIRST_FILE_ID,
                    "segment 1 holds the cancelled PUT and must survive");
            assertTrue(tombstonesOnDisk() > 0,
                    "an older PUT is still on disk, so the tombstone is still needed");
            assertNull(db.get(b("k")));
        }
        try (Bitcask db = Bitcask.open(dir, config())) {
            assertNull(db.get(b("k")), "the deleted key must not come back after a restart");
        }
    }

    @Test
    void repeatedMergesDoNotAccumulateTombstones() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int round = 0; round < 10; round++) {
                for (int k = 0; k < 6; k++) {
                    db.put(b("key:" + k), b("round:" + round));
                }
                db.delete(b("key:" + (round % 6)));
                db.merge();
            }
            // Six keys, so at most six deletions can be outstanding at once.
            assertTrue(tombstonesOnDisk() <= 6,
                    "tombstones should not pile up across merges: " + tombstonesOnDisk());

            for (int k = 0; k < 6; k++) {
                db.put(b("key:" + k), b("final"));
            }
            db.merge();
            assertEquals(0, tombstonesOnDisk(),
                    "every key was written again, so no tombstone has anything to cancel");
        }
    }
}
