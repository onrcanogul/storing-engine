package com.onurcanogul.bitcask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * What {@link SyncPolicy#ALWAYS} costs, and whether more threads help.
 *
 * <p>An fsync is a fixed cost per call, not per byte, so in principle one of them
 * could carry the writes of every thread waiting at that moment. Today it does
 * not: the fsync happens inside the write lock, so no second write can even be
 * appended while it runs. Every writer pays for its own.
 *
 * <p>The number that shows this is writes per fsync. At one, every write bought
 * its own trip to the disk. Above one, they are sharing.
 *
 * <p>Segments are large here on purpose. Rotation has an fsync of its own, and
 * this is measuring the other one.
 *
 * <p>Lives in this package rather than {@code bench} so that it can read the
 * engine's fsync counter. Not a test. Run it by hand:
 * <pre>
 * mvn -q test-compile
 * java -cp target/classes:target/test-classes com.onurcanogul.bitcask.SyncBench
 * </pre>
 */
public final class SyncBench {

    private static final int VALUE_SIZE = 512;
    private static final long RUN_MILLIS = 3_000;
    private static final int[] THREAD_COUNTS = {1, 2, 4, 8};

    private SyncBench() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("sync-bench");
        try {
            run(root.resolve("warmup"), 2, true);

            System.out.printf("%n%s, %d-byte values, %.0f s per run%n",
                    SyncPolicy.ALWAYS, VALUE_SIZE, RUN_MILLIS / 1000.0);
            System.out.println("latencies in microseconds");
            System.out.println();
            System.out.printf("%-9s %10s %10s %14s %9s %9s %9s%n",
                    "threads", "puts/sec", "fsync/sec", "puts per fsync", "p50", "p99", "p99.9");

            for (int threads : THREAD_COUNTS) {
                run(root.resolve("t" + threads), threads, false);
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void run(Path dir, int threads, boolean warmup) throws Exception {
        BitcaskConfig config = BitcaskConfig.defaults()
                .withMaxValueSize(VALUE_SIZE)
                .withMaxSegmentSize(64 * 1024 * 1024)
                .withSyncPolicy(SyncPolicy.ALWAYS);

        byte[] value = new byte[VALUE_SIZE];

        try (Bitcask db = Bitcask.open(dir, config)) {
            AtomicBoolean running = new AtomicBoolean(true);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Writer> writers = new ArrayList<>();
            List<Thread> running_threads = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                Writer writer = new Writer(db, value, t, running, ready, go);
                writers.add(writer);
                running_threads.add(Thread.ofPlatform().start(writer));
            }

            ready.await();
            long syncsBefore = db.syncCount.get();
            long started = System.nanoTime();
            go.countDown();

            Thread.sleep(RUN_MILLIS);
            running.set(false);

            for (Thread thread : running_threads) {
                thread.join(30_000);
            }
            long elapsed = System.nanoTime() - started;
            long syncs = db.syncCount.get() - syncsBefore;

            if (warmup) {
                return;
            }

            long[] all = merged(writers);
            double seconds = elapsed / 1_000_000_000.0;

            System.out.printf("%-9d %10.0f %10.0f %14.2f %9d %9d %9d%n",
                    threads,
                    all.length / seconds,
                    syncs / seconds,
                    syncs == 0 ? 0 : all.length / (double) syncs,
                    micros(percentile(all, 50.0)),
                    micros(percentile(all, 99.0)),
                    micros(percentile(all, 99.9)));
        }
    }

    /** One writer thread, recording how long each of its own puts took. */
    private static final class Writer implements Runnable {

        private final Bitcask db;
        private final byte[] value;
        private final int id;
        private final AtomicBoolean running;
        private final CountDownLatch ready;
        private final CountDownLatch go;

        private long[] latencies = new long[64];
        private int count;

        Writer(Bitcask db, byte[] value, int id, AtomicBoolean running,
               CountDownLatch ready, CountDownLatch go) {
            this.db = db;
            this.value = value;
            this.id = id;
            this.running = running;
            this.ready = ready;
            this.go = go;
        }

        @Override
        public void run() {
            try {
                ready.countDown();
                go.await();

                for (int i = 0; running.get(); i++) {
                    byte[] key = ("t" + id + ":" + i).getBytes(StandardCharsets.UTF_8);

                    long t0 = System.nanoTime();
                    db.put(key, value);
                    record(System.nanoTime() - t0);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private void record(long nanos) {
            if (count == latencies.length) {
                latencies = Arrays.copyOf(latencies, latencies.length * 2);
            }
            latencies[count++] = nanos;
        }
    }

    private static long[] merged(List<Writer> writers) {
        int total = writers.stream().mapToInt(w -> w.count).sum();
        long[] all = new long[total];
        int at = 0;
        for (Writer writer : writers) {
            System.arraycopy(writer.latencies, 0, all, at, writer.count);
            at += writer.count;
        }
        Arrays.sort(all);
        return all;
    }

    private static long micros(long nanos) {
        return nanos / 1000;
    }

    private static long percentile(long[] sorted, double percent) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(percent / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort on a temp directory.
                }
            });
        }
    }
}
