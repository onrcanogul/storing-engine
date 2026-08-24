package com.onurcanogul.bitcask.bench;

import com.onurcanogul.bitcask.Bitcask;
import com.onurcanogul.bitcask.BitcaskConfig;
import com.onurcanogul.bitcask.SyncPolicy;
import com.onurcanogul.bitcask.compaction.MergeReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * What compaction and an unpausing writer cost each other.
 *
 * <p>A merge takes the write lock to decide about a record and to copy it, so it
 * and the writer are competing for the same thing. Two numbers matter and they
 * pull against each other: how long the merge takes to finish, and what the
 * writer's latency looks like while it runs.
 *
 * <p>Both are reported, because a change that fixes one by ruining the other is
 * not a fix. The batch size the merge uses is the dial between them, and this is
 * the measurement it should be chosen from.
 *
 * <p>Not a test. Run it by hand:
 * <pre>
 * mvn -q test-compile
 * java -cp target/classes:target/test-classes com.onurcanogul.bitcask.bench.MergeBench
 * </pre>
 */
public final class MergeBench {

    private static final int COLD_KEYS = 2_000;
    private static final int FILLER_KEYS = 3;
    private static final int FILLER_ROUNDS = 40;
    private static final int HOT_KEYS = 200;

    /** Room for every latency the writer could record; unused slots are ignored. */
    private static final int LATENCY_CAPACITY = 4_000_000;

    private MergeBench() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("merge-bench");
        try {
            run(root.resolve("warmup"), true);

            System.out.println();
            System.out.printf("%-14s %10s %9s %12s %8s %8s %8s %8s%n",
                    "", "merge ms", "moved", "writer p/s", "p50", "p99", "p99.9", "max");
            run(root.resolve("measured"), false);
        } finally {
            deleteTree(root);
        }
    }

    private static void run(Path dir, boolean warmup) throws Exception {
        byte[] cold = b("cold");
        long[] latencies = new long[LATENCY_CAPACITY];
        AtomicInteger recorded = new AtomicInteger();

        BitcaskConfig config = BitcaskConfig.defaults()
                .withMaxValueSize(64)
                .withMaxSegmentSize(512)
                .withSyncPolicy(SyncPolicy.NEVER);

        try (Bitcask db = Bitcask.open(dir, config)) {
            // Live records interleaved with filler, so the segments holding them
            // are dirty enough to merge and still have something worth carrying.
            for (int i = 0; i < COLD_KEYS; i++) {
                db.put(b("cold:" + i), cold);
                for (int f = 0; f < FILLER_KEYS; f++) {
                    db.put(b("filler:" + f), b("seed"));
                }
            }
            for (int round = 0; round < FILLER_ROUNDS; round++) {
                for (int f = 0; f < FILLER_KEYS; f++) {
                    db.put(b("filler:" + f), b("round:" + round));
                }
            }

            AtomicBoolean running = new AtomicBoolean(true);
            Thread writer = Thread.ofPlatform().start(() -> {
                try {
                    while (running.get()) {
                        for (int i = 0; i < HOT_KEYS; i++) {
                            long t0 = System.nanoTime();
                            db.put(b("hot:" + i), b("hammer"));
                            long taken = System.nanoTime() - t0;

                            int at = recorded.getAndIncrement();
                            if (at < latencies.length) {
                                latencies[at] = taken;
                            }
                        }
                    }
                } catch (Exception stopping) {
                    // The store closes underneath the writer when the run ends.
                }
            });

            long started = System.nanoTime();
            MergeReport report = db.merge();
            long mergeNanos = System.nanoTime() - started;

            running.set(false);
            writer.join(10_000);

            if (warmup) {
                return;
            }

            int count = Math.min(recorded.get(), latencies.length);
            long[] sorted = Arrays.copyOf(latencies, count);
            Arrays.sort(sorted);

            double seconds = mergeNanos / 1_000_000_000.0;
            System.out.printf("%-14s %10d %9d %12.0f %8d %8d %8d %8d%n",
                    "batch=" + batchSizeInUse(),
                    TimeUnit.NANOSECONDS.toMillis(mergeNanos),
                    report.recordsMoved(),
                    count / seconds,
                    micros(percentile(sorted, 50.0)),
                    micros(percentile(sorted, 99.0)),
                    micros(percentile(sorted, 99.9)),
                    micros(sorted.length == 0 ? 0 : sorted[sorted.length - 1]));
        }
    }

    /**
     * The batch size is a constant inside the engine rather than a setting, so it
     * is swept by editing it and rebuilding. Reported here only as a label.
     */
    private static String batchSizeInUse() {
        return System.getProperty("bitcask.bench.label", "?");
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
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
