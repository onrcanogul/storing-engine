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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Many writes, one trip to the disk.
 *
 * <p>An fsync costs the same whether one record or a hundred are waiting behind
 * it, so under {@link SyncPolicy#ALWAYS} a writer does not need an fsync of its
 * own — it needs one to happen after its record was written. Writers that arrive
 * together share.
 *
 * <p>What cannot be tested here is the promise itself. Whether a write really
 * survives a power cut is not observable from a process, which is why the
 * assertion is on the number of fsyncs rather than on what survives. The
 * arithmetic is the evidence: fewer fsyncs than writes means writes were sharing.
 */
class GroupCommitTest {

    @TempDir
    Path dir;

    private static final int WRITERS = 4;
    private static final int PUTS_EACH = 50;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] key(int writer, int i) {
        return b("w" + writer + ":" + i);
    }

    private BitcaskConfig alwaysSyncing() {
        return BitcaskConfig.defaults()
                .withMaxValueSize(64)
                // Large enough that rotation is not what is being measured: it
                // fsyncs too, and would muddle the count.
                .withMaxSegmentSize(8 * 1024 * 1024)
                .withSyncPolicy(SyncPolicy.ALWAYS);
    }

    /** Runs the writers together and returns how many fsyncs the whole thing took. */
    private long writeConcurrently(Bitcask db) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();

        long syncsBefore = db.syncCount.get();

        for (int w = 0; w < WRITERS; w++) {
            int writer = w;
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    for (int i = 0; i < PUTS_EACH; i++) {
                        db.put(key(writer, i), b("v" + i));
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }));
        }

        go.countDown();
        for (Thread thread : threads) {
            assertTrue(thread.join(java.time.Duration.ofSeconds(60)), "a writer never finished");
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }

        return db.syncCount.get() - syncsBefore;
    }

    @Test
    @Timeout(120)
    void concurrentWritersShareTheirFsyncs() throws Exception {
        int total = WRITERS * PUTS_EACH;

        long syncs;
        try (Bitcask db = Bitcask.open(dir, alwaysSyncing())) {
            syncs = writeConcurrently(db);
        }

        // One fsync per write is what the engine used to do, and it is what any
        // implementation that forgets to release the lock before waiting will do
        // again. The margin is deliberately loose: how many writers happen to be
        // waiting when a batch closes is a scheduling matter, and the claim being
        // made is only that they share at all.
        assertTrue(syncs < total,
                "every write bought its own fsync: " + syncs + " fsyncs for " + total + " writes");
    }

    @Test
    @Timeout(120)
    void nothingIsLostWhileWritersShare() throws Exception {
        try (Bitcask db = Bitcask.open(dir, alwaysSyncing())) {
            writeConcurrently(db);
        }

        // Reopening rebuilds the index from the log alone, so this also proves the
        // records really were written and not merely acknowledged.
        try (Bitcask db = Bitcask.open(dir, alwaysSyncing())) {
            for (int w = 0; w < WRITERS; w++) {
                for (int i = 0; i < PUTS_EACH; i++) {
                    byte[] value = db.get(key(w, i));
                    assertNotNull(value, "w" + w + ":" + i + " is missing");
                    assertArrayEquals(b("v" + i), value);
                }
            }
            assertEquals(WRITERS * PUTS_EACH, db.size());
        }
    }

    @Test
    @Timeout(60)
    void aSingleWriterStillGetsItsOwnFsync() throws Exception {
        // Nobody to share with: the promise has to be kept the expensive way.
        try (Bitcask db = Bitcask.open(dir, alwaysSyncing())) {
            long before = db.syncCount.get();
            for (int i = 0; i < 20; i++) {
                db.put(key(0, i), b("v" + i));
            }
            assertEquals(20, db.syncCount.get() - before,
                    "a lone writer should have fsynced once per write");
        }
    }

    @Test
    @Timeout(60)
    void neverDoesNotFsyncOnTheWritePath() throws Exception {
        BitcaskConfig never = alwaysSyncing().withSyncPolicy(SyncPolicy.NEVER);

        try (Bitcask db = Bitcask.open(dir, never)) {
            long before = db.syncCount.get();
            for (int i = 0; i < 200; i++) {
                db.put(key(0, i), b("v" + i));
            }
            assertEquals(0, db.syncCount.get() - before,
                    "NEVER promises nothing about power loss and should pay for nothing");
        }
    }
}
