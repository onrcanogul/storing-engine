package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs merges while readers and a writer are working.
 *
 * <p>The interesting window is narrow: a reader has to be holding an index entry
 * that names a segment which a merge then deletes. Nothing here forces that
 * moment, so the test leans on volume instead — enough traffic that the window
 * is hit repeatedly, and a failure shows up as a thrown exception or a wrong
 * value rather than as a flaky assertion.
 *
 * <p>Every test here is bounded by wall clock rather than by a count of merges,
 * and every test carries a {@link Timeout}. A merge competes with the writer for
 * one lock and takes it once per record, so under a writer that never pauses the
 * merge advances at whatever rate it is granted — which can be close to none.
 * A fixed number of merge rounds is therefore not a bound at all: it hangs
 * instead of failing. Time is the only honest budget for work this contended.
 */
class ConcurrentMergeTest {

    @TempDir
    Path dir;

    private static final int KEY_SPACE = 40;

    /** How long each test lets its threads fight before it calls a halt. */
    private static final long RUN_MILLIS = 2_000;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] key(int i) {
        return b("key:" + i);
    }

    private BitcaskConfig config() {
        // Small enough that rotation happens constantly, but not so small that
        // the test measures fsync latency instead of concurrency: every rotation
        // fsyncs the outgoing segment while holding the write lock, so a
        // segment sized in the hundreds of bytes starves merges outright.
        return BitcaskConfig.defaults()
                .withMaxValueSize(64)
                .withMaxSegmentSize(4096);
    }

    /**
     * Keeps a background writer from monopolising the write lock.
     *
     * <p>Without this the writer reacquires the lock the instant it releases it —
     * {@code synchronized} makes no fairness promise — and a merge asking for the
     * same lock once per record never gets a turn.
     */
    private static void yieldToMerges() throws InterruptedException {
        Thread.sleep(1);
    }

    @Test
    @Timeout(60)
    void readersAndAWriterKeepWorkingThroughRepeatedMerges() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int i = 0; i < KEY_SPACE; i++) {
                db.put(key(i), b("initial"));
            }

            AtomicBoolean running = new AtomicBoolean(true);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicLong reads = new AtomicLong();
            AtomicLong writes = new AtomicLong();
            CountDownLatch started = new CountDownLatch(5);

            List<Thread> workers = new ArrayList<>();

            for (int r = 0; r < 4; r++) {
                workers.add(Thread.ofPlatform().unstarted(() -> {
                    started.countDown();
                    try {
                        while (running.get()) {
                            for (int i = 0; i < KEY_SPACE; i++) {
                                // Every key has been written, so a null here means
                                // a merge lost a record.
                                if (db.get(key(i)) == null) {
                                    throw new AssertionError("key:" + i + " vanished during a merge");
                                }
                                reads.incrementAndGet();
                            }
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }));
            }

            workers.add(Thread.ofPlatform().unstarted(() -> {
                started.countDown();
                try {
                    long round = 0;
                    while (running.get()) {
                        for (int i = 0; i < KEY_SPACE; i++) {
                            db.put(key(i), b("round:" + round));
                            writes.incrementAndGet();
                        }
                        round++;
                        yieldToMerges();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }));

            workers.forEach(Thread::start);
            assertTrue(started.await(5, TimeUnit.SECONDS), "workers did not start");

            long merges = 0;
            try {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RUN_MILLIS);
                while (System.nanoTime() < deadline && failure.get() == null) {
                    if (db.merge().didAnything()) {
                        merges++;
                    }
                }
            } finally {
                // Even if a merge throws, the workers have to be told to stop:
                // they are non-daemon threads and would otherwise outlive the test.
                running.set(false);
                for (Thread worker : workers) {
                    worker.join(10_000);
                }
            }

            if (failure.get() != null) {
                throw new AssertionError("a worker failed after " + reads.get() + " reads, "
                        + writes.get() + " writes, " + merges + " merges", failure.get());
            }

            assertTrue(merges > 0, "no merge ran; the test proved nothing");
            assertTrue(reads.get() > 10_000, "too few reads to be meaningful: " + reads.get());
            assertEquals(KEY_SPACE, db.size());
        }
    }

    @Test
    @Timeout(60)
    void deletesDuringMergesStayDeleted() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int i = 0; i < KEY_SPACE; i++) {
                db.put(key(i), b("initial"));
            }

            AtomicBoolean running = new AtomicBoolean(true);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            // Churn everything except the key under test, to keep segments dirty.
            Thread churn = Thread.ofPlatform().start(() -> {
                try {
                    long round = 0;
                    while (running.get()) {
                        for (int i = 1; i < KEY_SPACE; i++) {
                            db.put(key(i), b("round:" + round));
                        }
                        round++;
                        yieldToMerges();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });

            db.delete(key(0));

            long merges = 0;
            try {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RUN_MILLIS);
                while (System.nanoTime() < deadline) {
                    if (db.merge().didAnything()) {
                        merges++;
                    }
                    assertNull(db.get(key(0)), "the deleted key came back after " + merges + " merges");
                }
            } finally {
                running.set(false);
                churn.join(10_000);
            }

            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
            assertTrue(merges > 0, "no merge ran; the test proved nothing");
        }

        try (Bitcask db = Bitcask.open(dir, config())) {
            assertNull(db.get(key(0)), "the deletion must survive the reopen too");
        }
    }

    @Test
    @Timeout(60)
    void aValueWrittenDuringAMergeIsTheOneThatSurvives() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int round = 0; round < 6; round++) {
                for (int i = 0; i < KEY_SPACE; i++) {
                    db.put(key(i), b("old"));   // build up garbage
                }
            }

            AtomicBoolean running = new AtomicBoolean(true);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread writer = Thread.ofPlatform().start(() -> {
                try {
                    while (running.get()) {
                        for (int i = 0; i < KEY_SPACE; i++) {
                            db.put(key(i), b("new"));
                        }
                        yieldToMerges();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });

            long merges = 0;
            try {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RUN_MILLIS);
                while (System.nanoTime() < deadline) {
                    if (db.merge().didAnything()) {
                        merges++;
                    }
                }
            } finally {
                running.set(false);
                writer.join(10_000);
            }

            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
            assertTrue(merges > 0, "no merge ran; the test proved nothing");

            // A merge that resurrected a stale record would leave "old" behind.
            for (int i = 0; i < KEY_SPACE; i++) {
                assertArrayEquals(b("new"), db.get(key(i)), "key:" + i + " was rolled back by a merge");
            }
        }
    }
}
