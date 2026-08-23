package com.onurcanogul.bitcask;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Builds a store worth merging, then dies in the middle of the merge.
 *
 * <p>Runs in its own JVM because the crash has to be real: {@code halt} skips
 * shutdown hooks and {@code close()} exactly as a killed process would, leaving
 * the directory in whatever state the merge had reached.
 *
 * <p>The moment is chosen by name — see {@code Bitcask.MergeHookPoint} — because
 * the windows that matter are between a copy and a delete and pass in
 * microseconds. Killing from outside would never land in one.
 */
public final class MergeCrashMain {

    /** Keys that must still be readable after the crash. */
    static final int LIVE_KEYS = 12;

    /** Keys that were deleted before the merge and must stay deleted. */
    static final int DELETED_KEYS = 6;

    /**
     * Keys rewritten over and over, to make the segments around them garbage.
     *
     * <p>Without them the merge would have nothing to copy: a store where every
     * old segment is pure garbage is compacted by deleting the segments, and a
     * crash test that never crashes mid-copy is not testing much.
     */
    static final int FILLER_KEYS = 3;

    /** The exit status the child uses, so the parent can tell a crash from an exit. */
    static final int CRASH_STATUS = 9;

    private MergeCrashMain() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        String crashAt = args[1];

        BitcaskConfig config = BitcaskConfig.defaults()
                .withMaxValueSize(64)
                // Small segments: many of them, each easily dirtied, so the merge
                // has plenty of candidates and plenty of moments to be killed in.
                .withMaxSegmentSize(512);

        // Deliberately not in try-with-resources: this process is meant to die
        // without ever closing the engine.
        Bitcask db = Bitcask.open(dir, config);

        // Every live key is laid down once, interleaved with filler, so that the
        // segments holding them end up part garbage and part treasure. That mix
        // is what forces the merge to copy rather than simply delete.
        for (int i = 0; i < LIVE_KEYS; i++) {
            db.put(liveKey(i), finalValue(i));
            for (int f = 0; f < FILLER_KEYS; f++) {
                db.put(fillerKey(f), b("seed"));
            }
        }
        for (int i = 0; i < DELETED_KEYS; i++) {
            db.put(deletedKey(i), b("doomed"));
        }

        // Rewriting the filler is what turns those closed segments into garbage.
        for (int round = 0; round < 12; round++) {
            for (int f = 0; f < FILLER_KEYS; f++) {
                db.put(fillerKey(f), b("round:" + round));
            }
        }

        for (int i = 0; i < DELETED_KEYS; i++) {
            db.delete(deletedKey(i));
        }

        // More churn, so the segments holding the tombstones close and dirty too.
        for (int round = 12; round < 24; round++) {
            for (int f = 0; f < FILLER_KEYS; f++) {
                db.put(fillerKey(f), b("round:" + round));
            }
        }

        db.mergeHook = point -> {
            if (crashAt.equals(point)) {
                Runtime.getRuntime().halt(CRASH_STATUS);
            }
        };

        System.out.println("ready");
        System.out.flush();

        db.merge();

        // Reaching this line means the hook never fired: either nothing was worth
        // merging or the point was never passed. Either way the test proved
        // nothing, and a clean exit would let it pass by accident.
        System.out.println("merge finished without crashing at " + crashAt);
        System.out.flush();
        System.exit(1);
    }

    static byte[] liveKey(int i) {
        return b("live:" + i);
    }

    static byte[] deletedKey(int i) {
        return b("deleted:" + i);
    }

    static byte[] fillerKey(int i) {
        return b("filler:" + i);
    }

    static byte[] finalValue(int i) {
        return b("final:" + i);
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
