package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Applies the same random operation sequence to the engine and to a plain
 * HashMap, comparing after every step.
 *
 * <p>Hand-written tests only cover the situations someone thought of. This one
 * goes looking for the ones nobody did.
 *
 * <p>Every assertion carries the seed, so a failure can be reproduced exactly by
 * pinning that value.
 */
class ModelBasedTest {

    @TempDir
    Path dir;

    /** Small on purpose: overwrites and deletes should collide constantly. */
    private static final int KEY_SPACE = 50;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String keyAt(int i) {
        return "key:" + i;
    }

    private void assertMatchesModel(Bitcask db, Map<String, byte[]> model, String where)
            throws Exception {
        assertEquals(model.size(), db.size(), where + " — index size");
        for (int i = 0; i < KEY_SPACE; i++) {
            String key = keyAt(i);
            assertArrayEquals(model.get(key), db.get(b(key)), where + " — " + key);
        }
    }

    @Test
    void engineMatchesAHashMapReference() throws Exception {
        long seed = System.nanoTime();
        Random random = new Random(seed);
        Map<String, byte[]> model = new HashMap<>();

        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            for (int op = 0; op < 5_000; op++) {
                String key = keyAt(random.nextInt(KEY_SPACE));
                byte[] keyBytes = b(key);
                String where = "seed=" + seed + " op=" + op;
                int roll = random.nextInt(10);

                if (roll < 6) {
                    byte[] value = new byte[random.nextInt(64)];
                    random.nextBytes(value);

                    db.put(keyBytes, value);
                    model.put(key, value);

                } else if (roll < 8) {
                    boolean removedFromEngine = db.delete(keyBytes);
                    boolean removedFromModel = model.remove(key) != null;

                    assertEquals(removedFromModel, removedFromEngine, where + " — delete result");

                } else {
                    assertArrayEquals(model.get(key), db.get(keyBytes), where + " — get");
                }
            }

            assertMatchesModel(db, model, "seed=" + seed + " final");
        }

        // Recovery has to rebuild exactly the same state.
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertMatchesModel(db, model, "seed=" + seed + " after reopen");
        }
    }

    @Test
    void stateSurvivesReopensAtArbitraryPoints() throws Exception {
        long seed = System.nanoTime();
        Random random = new Random(seed);
        Map<String, byte[]> model = new HashMap<>();

        Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults());
        try {
            for (int op = 0; op < 2_000; op++) {
                String key = keyAt(random.nextInt(KEY_SPACE));
                byte[] keyBytes = b(key);
                String where = "seed=" + seed + " op=" + op;

                int roll = random.nextInt(10);
                if (roll < 6) {
                    byte[] value = new byte[random.nextInt(64)];
                    random.nextBytes(value);

                    db.put(keyBytes, value);
                    model.put(key, value);

                } else if (roll < 8) {
                    assertEquals(model.remove(key) != null, db.delete(keyBytes),
                            where + " — delete result");

                } else {
                    assertArrayEquals(model.get(key), db.get(keyBytes), where + " — get");
                }

                // Close and reopen at unpredictable moments: recovery has to be
                // correct from any state, not just from a tidy one.
                if (random.nextInt(100) < 3) {
                    db.close();
                    db = Bitcask.open(dir, BitcaskConfig.defaults());

                    assertEquals(0, db.recoveryReport().bytesDiscarded(),
                            where + " — a clean close must lose nothing");
                    assertMatchesModel(db, model, where + " — after reopen");
                }
            }

            assertMatchesModel(db, model, "seed=" + seed + " final");
        } finally {
            db.close();
        }
    }

    @Test
    void engineMatchesTheModelWithFsyncOnEveryWrite() throws Exception {
        long seed = System.nanoTime();
        Random random = new Random(seed);
        Map<String, byte[]> model = new HashMap<>();

        BitcaskConfig synced = new BitcaskConfig(
                BitcaskConfig.DEFAULT_MAX_VALUE_SIZE,
                SyncPolicy.ALWAYS,
                com.onurcanogul.bitcask.recovery.RecoveryMode.TOLERATE_TAIL);

        try (Bitcask db = Bitcask.open(dir, synced)) {
            for (int op = 0; op < 500; op++) {
                String key = keyAt(random.nextInt(KEY_SPACE));

                if (random.nextInt(10) < 7) {
                    byte[] value = new byte[random.nextInt(32)];
                    random.nextBytes(value);

                    db.put(b(key), value);
                    model.put(key, value);
                } else {
                    assertEquals(model.remove(key) != null, db.delete(b(key)),
                            "seed=" + seed + " op=" + op);
                }
            }
            assertMatchesModel(db, model, "seed=" + seed + " fsync=ALWAYS");
        }
    }
}
