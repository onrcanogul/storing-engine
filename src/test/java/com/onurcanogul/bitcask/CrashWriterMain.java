package com.onurcanogul.bitcask;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Runs in a separate JVM so the parent can kill it with SIGKILL.
 *
 * <p>Each key is printed only <em>after</em> {@code put} returns, so the parent
 * knows exactly which writes completed. Anything printed is a write the engine
 * acknowledged, and must survive the crash.
 */
public final class CrashWriterMain {

    private CrashWriterMain() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        SyncPolicy syncPolicy = SyncPolicy.valueOf(args[1]);

        BitcaskConfig config = BitcaskConfig.defaults().withSyncPolicy(syncPolicy);

        // Deliberately not in try-with-resources: this process is meant to die
        // without ever closing the engine.
        Bitcask db = Bitcask.open(dir, config);

        for (int i = 0; ; i++) {
            db.put(key(i), value(i));

            System.out.println(i);
            System.out.flush();
        }
    }

    static byte[] key(int i) {
        return ("key:" + i).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] value(int i) {
        return ("value:" + i).getBytes(StandardCharsets.UTF_8);
    }
}
