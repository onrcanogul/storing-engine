package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.compaction.MergeReport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compaction has to finish, even against a writer that never pauses.
 *
 * <p>This is not a performance test. An engine whose compaction cannot keep up
 * with its writes is broken in a way no benchmark measures: garbage is never
 * reclaimed and the store grows without bound while every operation still
 * reports success. Slow compaction and no compaction are the same outcome given
 * enough time.
 *
 * <p>The pressure here is deliberate and unrealistic — one thread doing nothing
 * but writing, with no pause anywhere. A merge that survives that survives
 * ordinary load with room to spare, and the failure it is guarding against is
 * exactly the one that only shows up when the machine is busy.
 */
class MergeLivenessTest {

    @TempDir
    Path dir;

    /** Records the merge must carry forward. */
    private static final int COLD_KEYS = 2_000;

    /** Keys rewritten over and over, to turn the segments around them into garbage. */
    private static final int FILLER_KEYS = 3;

    private static final int FILLER_ROUNDS = 40;

    /** Keys the competing writer hammers. */
    private static final int HOT_KEYS = 200;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Keys the merge will have to carry forward. */
    private static byte[] coldKey(int i) {
        return b("cold:" + i);
    }

    private static byte[] fillerKey(int i) {
        return b("filler:" + i);
    }

    /**
     * Keys the competing writer hammers.
     *
     * <p>Deliberately a different range. If the writer overwrote the same keys,
     * every record the merge looked at would already be stale and the copy path —
     * the expensive half, the one that appends while holding the lock — would
     * never run at all.
     */
    private static byte[] hotKey(int i) {
        return b("hot:" + i);
    }

    @Test
    @Timeout(30)
    void aMergeFinishesWhileAWriterNeverPauses() throws Exception {
        BitcaskConfig config = BitcaskConfig.defaults()
                .withMaxValueSize(64)
                .withMaxSegmentSize(512);

        try (Bitcask db = Bitcask.open(dir, config)) {
            // Each cold key is laid down once, interleaved with filler that will
            // shortly be garbage, so the segments holding them end up part rubbish
            // and part treasure. A segment that is pure garbage is compacted by
            // deleting it; only a mixed one has to be copied out record by record,
            // which is the work being measured here.
            for (int i = 0; i < COLD_KEYS; i++) {
                db.put(coldKey(i), b("cold"));
                for (int f = 0; f < FILLER_KEYS; f++) {
                    db.put(fillerKey(f), b("seed"));
                }
            }
            for (int round = 0; round < FILLER_ROUNDS; round++) {
                for (int f = 0; f < FILLER_KEYS; f++) {
                    db.put(fillerKey(f), b("round:" + round));
                }
            }

            AtomicBoolean running = new AtomicBoolean(true);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicLong writes = new AtomicLong();

            Thread writer = Thread.ofPlatform().start(() -> {
                try {
                    while (running.get()) {
                        for (int i = 0; i < HOT_KEYS; i++) {
                            db.put(hotKey(i), b("hammer"));
                            writes.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });

            try {
                long started = System.nanoTime();
                MergeReport report = db.merge();
                long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

                assertTrue(report.recordsMoved() > 0,
                        "the merge copied nothing forward, so it never exercised the path "
                                + "that holds the lock to append");
                System.out.printf("merge finished in %d ms, moving %d records, "
                                + "against %d competing writes%n",
                        millis, report.recordsMoved(), writes.get());
            } finally {
                running.set(false);
                writer.join(10_000);
            }

            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
        }
    }
}
