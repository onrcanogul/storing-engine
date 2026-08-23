package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.format.FileHeader;
import com.onurcanogul.bitcask.recovery.RecoveryReport;
import com.onurcanogul.bitcask.store.SegmentFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kills a writer that is rotating segments constantly.
 *
 * <p>Rotation is several syscalls — fsync, create, write header — and SIGKILL is
 * delivered between them rather than inside one. That makes this one of the few
 * half-finished states a {@code kill -9} can actually produce, and the claim
 * that rotation is crash-safe is worth more once it has been observed rather
 * than only argued.
 */
class RotationCrashTest {

    @TempDir
    Path dir;

    /** Records are ~40 bytes, so a 1 KB segment holds roughly 25 of them. */
    private static final int SEGMENT_SIZE = 1024;

    private static final int WRITES_BEFORE_KILL = 2_000;

    private BitcaskConfig config() {
        return BitcaskConfig.defaults()
                .withMaxValueSize(64)
                .withMaxSegmentSize(SEGMENT_SIZE);
    }

    private int runWriterUntilKilled() throws Exception {
        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                CrashWriterMain.class.getName(),
                dir.toAbsolutePath().toString(),
                SyncPolicy.NEVER.name(),
                String.valueOf(SEGMENT_SIZE))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();

        int lastAcknowledged = -1;
        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = out.readLine()) != null) {
                lastAcknowledged = Integer.parseInt(line.trim());
                if (lastAcknowledged >= WRITES_BEFORE_KILL) {
                    child.destroyForcibly();
                    break;
                }
            }
        } catch (IOException expectedWhenTheProcessDies) {
            // The pipe breaks as the child is killed; that is the point.
        }

        assertTrue(child.waitFor(30, TimeUnit.SECONDS), "child process did not die");
        assertTrue(lastAcknowledged >= WRITES_BEFORE_KILL,
                "child stopped early, only reached " + lastAcknowledged);

        return lastAcknowledged;
    }

    @Test
    void theKillLandsWhileSegmentsAreRotating() throws Exception {
        runWriterUntilKilled();

        List<Integer> segments = SegmentFiles.listFileIds(dir);
        assertTrue(segments.size() > 20,
                "the test is only meaningful with many rotations, found " + segments.size());
    }

    @Test
    void everyAcknowledgedWriteSurvivesACrashDuringRotation() throws Exception {
        int lastAcknowledged = runWriterUntilKilled();

        try (Bitcask db = Bitcask.open(dir, config())) {
            RecoveryReport report = db.recoveryReport();

            for (int i = 0; i <= lastAcknowledged; i++) {
                byte[] value = db.get(CrashWriterMain.key(i));
                assertNotNull(value, "lost an acknowledged write: key:" + i
                        + " (report=" + report + ")");
                assertArrayEquals(CrashWriterMain.value(i), value, "wrong value for key:" + i);
            }
        }
    }

    @Test
    void everySegmentLeftBehindIsAValidSegment() throws Exception {
        runWriterUntilKilled();

        // Opening validates every header; a rotation interrupted before the
        // header was written leaves a short file, which open() repairs.
        try (Bitcask db = Bitcask.open(dir, config())) {
            assertEquals(SegmentFiles.listFileIds(dir).size(), db.segmentCount());
        }

        for (int fileId : SegmentFiles.listFileIds(dir)) {
            long size = Files.size(SegmentFiles.pathOf(dir, fileId));
            assertTrue(size >= FileHeader.SIZE,
                    "segment " + fileId + " is only " + size + " bytes");
            assertTrue(size <= SEGMENT_SIZE,
                    "segment " + fileId + " grew past the threshold at " + size + " bytes");
        }
    }

    @Test
    void noClosedSegmentIsReportedAsDamaged() throws Exception {
        runWriterUntilKilled();

        // Closed segments were fsynced at rotation, so recovery refuses to
        // tolerate damage in them. Opening at all proves none was damaged.
        try (Bitcask db = Bitcask.open(dir, config())) {
            RecoveryReport report = db.recoveryReport();

            assertTrue(report.truncatedAtOffset() == -1
                            || report.truncatedAtOffset() >= FileHeader.SIZE,
                    "any truncation must be inside the active segment: " + report);
        }
    }

    @Test
    void theEngineKeepsRotatingAfterTheCrash() throws Exception {
        int lastAcknowledged = runWriterUntilKilled();
        int segmentsBefore = SegmentFiles.listFileIds(dir).size();

        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int i = 0; i < 200; i++) {
                db.put(("after:" + i).getBytes(StandardCharsets.UTF_8),
                        ("value:" + i).getBytes(StandardCharsets.UTF_8));
            }
            assertArrayEquals("value:199".getBytes(StandardCharsets.UTF_8),
                    db.get("after:199".getBytes(StandardCharsets.UTF_8)));
        }

        assertTrue(SegmentFiles.listFileIds(dir).size() > segmentsBefore,
                "writing after the crash should have produced new segments");

        try (Bitcask db = Bitcask.open(dir, config())) {
            assertNotNull(db.get(CrashWriterMain.key(lastAcknowledged)), "pre-crash data lost");
            assertNotNull(db.get("after:0".getBytes(StandardCharsets.UTF_8)), "post-crash data lost");
        }
    }
}
