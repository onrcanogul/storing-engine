package com.onurcanogul.bitcask.bench;

import com.onurcanogul.bitcask.Bitcask;
import com.onurcanogul.bitcask.BitcaskConfig;
import com.onurcanogul.bitcask.SyncPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.Stream;

/**
 * What a rotation costs the write that happens to trigger it.
 *
 * <p>Writes are serialised, so whatever a rotation does is done with every other
 * writer queued behind it. This measures two populations separately — the writes
 * that rotated and the writes that did not — because an average over both hides
 * exactly the thing worth seeing.
 *
 * <p>Not a test. Run it by hand:
 * <pre>
 * mvn -q test-compile
 * java -cp target/classes:target/test-classes com.onurcanogul.bitcask.bench.RotationBench
 * </pre>
 */
public final class RotationBench {

    private static final int VALUE_SIZE = 512;
    private static final int PUTS = 100_000;
    private static final int WARMUP_PUTS = 20_000;

    private RotationBench() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("rotation-bench");
        try {
            warmUp(root);

            System.out.printf("%n%d puts of %d-byte values, SyncPolicy.NEVER, one writer%n",
                    PUTS, VALUE_SIZE);
            System.out.println("all times in microseconds");
            System.out.println();
            header();

            for (int segmentSize : new int[] {64 * 1024, 512 * 1024, 4 * 1024 * 1024}) {
                run(root, segmentSize).print();
            }
        } finally {
            deleteTree(root);
        }
    }

    /** Runs enough to get the JIT past interpreting, and throws the numbers away. */
    private static void warmUp(Path root) throws IOException {
        Path dir = root.resolve("warmup");
        byte[] value = valueBytes();
        try (Bitcask db = Bitcask.open(dir, config(64 * 1024))) {
            for (int i = 0; i < WARMUP_PUTS; i++) {
                db.put(key(i), value);
            }
        }
    }

    private static Result run(Path root, int segmentSize) throws IOException {
        Path dir = root.resolve("seg-" + segmentSize);
        byte[] value = valueBytes();

        long[] latencies = new long[PUTS];
        boolean[] rotated = new boolean[PUTS];

        long started = System.nanoTime();
        try (Bitcask db = Bitcask.open(dir, config(segmentSize))) {
            int segmentsBefore = db.segmentCount();

            for (int i = 0; i < PUTS; i++) {
                long t0 = System.nanoTime();
                db.put(key(i), value);
                latencies[i] = System.nanoTime() - t0;

                int segmentsNow = db.segmentCount();
                rotated[i] = segmentsNow != segmentsBefore;
                segmentsBefore = segmentsNow;
            }
        }
        long elapsedNanos = System.nanoTime() - started;

        return Result.of(segmentSize, latencies, rotated, elapsedNanos);
    }

    private record Result(int segmentSize, long rotations, double throughput,
                          double meanPlain, double meanRotating,
                          long p50, long p99, long p999, long max) {

        static Result of(int segmentSize, long[] latencies, boolean[] rotated, long elapsedNanos) {
            long rotations = 0;
            long plainTotal = 0;
            long rotatingTotal = 0;

            for (int i = 0; i < latencies.length; i++) {
                if (rotated[i]) {
                    rotations++;
                    rotatingTotal += latencies[i];
                } else {
                    plainTotal += latencies[i];
                }
            }

            long[] sorted = latencies.clone();
            Arrays.sort(sorted);

            return new Result(segmentSize,
                    rotations,
                    latencies.length / (elapsedNanos / 1_000_000_000.0),
                    micros(plainTotal / (double) (latencies.length - rotations)),
                    rotations == 0 ? 0 : micros(rotatingTotal / (double) rotations),
                    micros((long) percentile(sorted, 50.0)),
                    micros((long) percentile(sorted, 99.0)),
                    micros((long) percentile(sorted, 99.9)),
                    micros(sorted[sorted.length - 1]));
        }

        void print() {
            System.out.printf("%-10s %9d %12.0f %10.1f %12.1f %8d %8d %8d %8d%n",
                    humanSize(segmentSize), rotations, throughput,
                    meanPlain, meanRotating, p50, p99, p999, max);
        }
    }

    private static void header() {
        System.out.printf("%-10s %9s %12s %10s %12s %8s %8s %8s %8s%n",
                "segment", "rotations", "puts/sec", "mean plain", "mean rotate",
                "p50", "p99", "p99.9", "max");
    }

    private static BitcaskConfig config(int segmentSize) {
        return BitcaskConfig.defaults()
                .withMaxValueSize(VALUE_SIZE)
                .withMaxSegmentSize(segmentSize)
                .withSyncPolicy(SyncPolicy.NEVER);
    }

    private static byte[] valueBytes() {
        byte[] value = new byte[VALUE_SIZE];
        new Random(11).nextBytes(value);
        return value;
    }

    private static byte[] key(int i) {
        return ("key:" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static double micros(double nanos) {
        return nanos / 1000.0;
    }

    private static long micros(long nanos) {
        return nanos / 1000;
    }

    private static double percentile(long[] sorted, double percent) {
        int index = (int) Math.ceil(percent / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static String humanSize(int bytes) {
        return bytes >= 1024 * 1024 ? (bytes / (1024 * 1024)) + " MB" : (bytes / 1024) + " KB";
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
