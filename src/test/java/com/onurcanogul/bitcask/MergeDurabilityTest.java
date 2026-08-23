package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the merge to the order durability depends on.
 *
 * <p>A merge copies live records forward and then deletes the segments they came
 * from. Those are two different kinds of write: the copies land in the page cache
 * and the deletes land in the filesystem's metadata, and nothing makes the first
 * reach the disk before the second. Lose power in between and the sources are
 * gone while the copies never existed — records vanish outright, which is the one
 * outcome the whole design is built to avoid.
 *
 * <p>Crashing a process cannot show this. {@code kill -9} takes the process, not
 * the page cache; the kernel writes the pages out afterwards and the data is
 * there. Only real power loss tells the difference, so the assertion here is on
 * the order of the calls rather than on what survives.
 */
class MergeDurabilityTest {

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theCopiesReachTheDiskBeforeTheSourcesAreDeleted() throws Exception {
        BitcaskConfig config = BitcaskConfig.defaults()
                .withMaxValueSize(64)
                .withMaxSegmentSize(512)
                .withSyncPolicy(SyncPolicy.NEVER);

        try (Bitcask db = Bitcask.open(dir, config)) {
            // Enough rewriting of the same keys that closed segments turn to garbage.
            for (int round = 0; round < 12; round++) {
                for (int i = 0; i < 8; i++) {
                    db.put(b("key:" + i), b("round:" + round));
                }
            }

            AtomicLong syncsBeforeFirstDrop = new AtomicLong(-1);
            db.mergeHook = point -> {
                if (Bitcask.MergeHookPoint.BEFORE_DROP.equals(point)) {
                    syncsBeforeFirstDrop.set(db.syncCount.get());
                }
            };

            long syncsAtStart = db.syncCount.get();
            assertTrue(db.merge().didAnything(), "no merge ran; the test proved nothing");

            assertTrue(syncsBeforeFirstDrop.get() > syncsAtStart,
                    "the merge deleted its sources without fsyncing the copies first: "
                            + syncsAtStart + " syncs at the start, "
                            + syncsBeforeFirstDrop.get() + " before the first delete");
        }
    }
}
