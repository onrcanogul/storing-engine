package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kills a JVM in the middle of a merge and reopens the wreckage.
 *
 * <p>A merge is the one operation that deletes data on purpose, so it is the one
 * place a crash could turn compaction into loss. The claim being tested is the
 * one {@code merge()} makes in its own documentation: a crash partway through
 * leaves duplicates, never gaps. The copies carry higher sequence numbers, so
 * recovery prefers them, and whatever sources were not deleted are simply
 * garbage for the next merge to collect.
 *
 * <p>What this cannot test: fsync. Killing a process does not empty the page
 * cache — the kernel still writes those pages out — so an engine that never
 * fsyncs passes every test in this file. The ordering that protects against
 * real power loss is held up by {@link MergeDurabilityTest} instead.
 */
class MergeCrashTest {

    @TempDir
    Path dir;

    /**
     * Runs a merge in its own JVM that halts at {@code crashAt}.
     *
     * <p>The child is expected to die by its own hand rather than finish: a clean
     * exit means the crash point was never reached and the test would otherwise
     * pass having proved nothing.
     */
    private void crashDuringMergeAt(String crashAt) throws Exception {
        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                MergeCrashMain.class.getName(),
                dir.toAbsolutePath().toString(),
                crashAt)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();

        String firstLine;
        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            firstLine = out.readLine();
        }

        assertEquals("ready", firstLine, "the child never got as far as the merge");
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "child process did not die");
        assertEquals(MergeCrashMain.CRASH_STATUS, child.exitValue(),
                "the child did not crash at " + crashAt + "; the merge ran to completion");
    }

    /**
     * The invariant, whatever the merge had managed to do before it died: every
     * live key readable and current, every deleted key still gone.
     */
    private void assertStoreIsIntact(Bitcask db) throws Exception {
        for (int i = 0; i < MergeCrashMain.LIVE_KEYS; i++) {
            byte[] value = db.get(MergeCrashMain.liveKey(i));
            assertNotNull(value, "live:" + i + " was lost by a crashed merge");
            assertArrayEquals(MergeCrashMain.finalValue(i), value,
                    "live:" + i + " was rolled back to an older value by a crashed merge");
        }
        for (int i = 0; i < MergeCrashMain.DELETED_KEYS; i++) {
            assertNull(db.get(MergeCrashMain.deletedKey(i)),
                    "deleted:" + i + " came back from a crashed merge");
        }
        assertEquals(MergeCrashMain.LIVE_KEYS + MergeCrashMain.FILLER_KEYS, db.size(),
                "the key count changed across a crashed merge");
    }

    /** Reopens the crashed directory and checks it, then checks it again after a full merge. */
    private void assertRecoversAndStaysMergeable() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults()
                .withMaxValueSize(64)
                .withMaxSegmentSize(512))) {

            assertStoreIsIntact(db);

            // The leftovers a crashed merge leaves behind are ordinary garbage.
            // Finishing the job must not disturb anything the first pass decided.
            db.merge();
            assertStoreIsIntact(db);
        }
    }

    @Test
    @Timeout(120)
    void aCrashWhileCopyingLosesNothing() throws Exception {
        crashDuringMergeAt(Bitcask.MergeHookPoint.MID_COPY);
        assertRecoversAndStaysMergeable();
    }

    @Test
    @Timeout(120)
    void aCrashAfterCopyingButBeforeDeletingLeavesDuplicatesNotGaps() throws Exception {
        crashDuringMergeAt(Bitcask.MergeHookPoint.BEFORE_DROP);
        assertRecoversAndStaysMergeable();
    }

    @Test
    @Timeout(120)
    void aCrashPartWayThroughDeletingSourcesKeepsDeletionsDeleted() throws Exception {
        crashDuringMergeAt(Bitcask.MergeHookPoint.MID_DROP);
        assertRecoversAndStaysMergeable();
    }
}
