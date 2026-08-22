package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.recovery.RecoveryReport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The direct test of the phase's success criterion:
 *
 * <blockquote>Kill the process with {@code kill -9} at any arbitrary moment,
 * then reopen. Every write whose {@code put} returned must be present, and any
 * partially written record must be discarded without corrupting the
 * engine.</blockquote>
 *
 * <p>Every other recovery test simulates damage by editing the file. This one
 * uses a real crash.
 */
class CrashTest {

    @TempDir
    Path dir;

    private static final int WRITES_BEFORE_KILL = 2_000;

    /**
     * Starts a writer in its own JVM and SIGKILLs it once it has acknowledged
     * enough writes.
     *
     * @return the highest key index the writer confirmed before dying
     */
    private int runWriterUntilKilled(SyncPolicy syncPolicy) throws Exception {
        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                CrashWriterMain.class.getName(),
                dir.toAbsolutePath().toString(),
                syncPolicy.name())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();

        int lastAcknowledged = -1;
        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = out.readLine()) != null) {
                lastAcknowledged = Integer.parseInt(line.trim());
                if (lastAcknowledged >= WRITES_BEFORE_KILL) {
                    // SIGKILL: no cleanup, no shutdown hook, no close().
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
    void everyAcknowledgedWriteSurvivesSigkill() throws Exception {
        int lastAcknowledged = runWriterUntilKilled(SyncPolicy.NEVER);

        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
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
    void theEngineIsUsableAfterACrash() throws Exception {
        int lastAcknowledged = runWriterUntilKilled(SyncPolicy.NEVER);

        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put("after-crash".getBytes(StandardCharsets.UTF_8),
                    "ok".getBytes(StandardCharsets.UTF_8));

            assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8),
                    db.get("after-crash".getBytes(StandardCharsets.UTF_8)));

            // The pre-crash data is still there alongside the new write.
            assertNotNull(db.get(CrashWriterMain.key(lastAcknowledged)));

            // At least everything acknowledged, plus the new key. The child may
            // have written more after the last line the parent managed to read:
            // destroyForcibly() is asynchronous and the pipe is buffered, so the
            // parent only ever knows a lower bound on the child's progress.
            assertTrue(db.size() >= lastAcknowledged + 2,
                    "index holds " + db.size() + " keys but at least "
                            + (lastAcknowledged + 2) + " were expected");
        }
    }

    @Test
    void aTornTailIsDiscardedAndAccountedFor() throws Exception {
        int lastAcknowledged = runWriterUntilKilled(SyncPolicy.NEVER);
        long sizeAtCrash = Files.size(dir.resolve("data.log"));

        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            RecoveryReport report = db.recoveryReport();

            // Whatever was discarded, it is exactly what recovery says it is.
            assertEquals(sizeAtCrash - report.bytesDiscarded(),
                    Files.size(dir.resolve("data.log")),
                    "the file size must match what the report claims was discarded");

            // Nothing acknowledged may be among the losses.
            assertTrue(report.recordsReplayed() >= lastAcknowledged + 1,
                    "replayed " + report.recordsReplayed()
                            + " records but " + (lastAcknowledged + 1) + " writes were acknowledged");
        }
    }

    @Test
    void everyAcknowledgedWriteSurvivesSigkillWithFsyncOnEveryWrite() throws Exception {
        int lastAcknowledged = runWriterUntilKilled(SyncPolicy.ALWAYS);

        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            for (int i = 0; i <= lastAcknowledged; i++) {
                assertNotNull(db.get(CrashWriterMain.key(i)), "lost key:" + i + " under fsync=ALWAYS");
            }
        }
    }
}
