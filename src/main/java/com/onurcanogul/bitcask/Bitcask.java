package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.format.FileHeader;
import com.onurcanogul.bitcask.index.KeyDirEntry;
import com.onurcanogul.bitcask.store.DirectoryLock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An append-only key-value store with an in-memory index.
 *
 * <p>Every write is appended to a single log file, and the index maps each live
 * key to the offset of its most recent record. A read is therefore always
 * exactly one positional read, whatever the size of the data. The price is that
 * every key must fit in memory.
 *
 * <p>One writer, many readers: writes are serialized on this instance while
 * reads take no lock. One process per directory, enforced by
 * {@link DirectoryLock}.
 */
public final class Bitcask implements AutoCloseable {

    public static final String DATA_FILE_NAME = "data.log";

    private final BitcaskConfig config;
    private final DirectoryLock lock;
    private final FileChannel channel;
    private final Map<ByteBuffer, KeyDirEntry> keyDir;
    private final RecoveryReport report;

    private long writePos;
    private long nextSeq;

    private volatile boolean closed;

    private Bitcask(BitcaskConfig config,
                    DirectoryLock lock,
                    FileChannel channel,
                    Map<ByteBuffer, KeyDirEntry> keyDir,
                    RecoveryReport report,
                    long writePos,
                    long nextSeq) {
        this.config = config;
        this.lock = lock;
        this.channel = channel;
        this.keyDir = keyDir;
        this.report = report;
        this.writePos = writePos;
        this.nextSeq = nextSeq;
    }

    /**
     * Opens the store in {@code dir}, creating the directory if necessary.
     *
     * @throws IOException if the directory is already open, or the log is not one
     *                     of ours, or it is damaged and the configured
     *                     {@link RecoveryMode} refuses to continue
     */
    public static Bitcask open(Path dir, BitcaskConfig config) throws IOException {
        Files.createDirectories(dir);
        DirectoryLock lock = DirectoryLock.acquire(dir);

        FileChannel channel = null;
        try {
            Path logFile = dir.resolve(DATA_FILE_NAME);
            boolean fresh = !Files.exists(logFile) || Files.size(logFile) == 0;

            channel = FileChannel.open(logFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);

            if (fresh) {
                FileHeader.write(channel);
            } else {
                FileHeader.validate(channel);
            }

            Map<ByteBuffer, KeyDirEntry> keyDir = new ConcurrentHashMap<>();

            // A real replay replaces this once recovery exists.
            RecoveryReport report = new RecoveryReport(0, 0, 0, -1, StopReason.CLEAN_EOF);

            return new Bitcask(config, lock, channel, keyDir, report, channel.size(), 1L);

        } catch (IOException | RuntimeException e) {
            // A half-finished open must not leave the directory locked forever.
            closeQuietly(channel);
            releaseQuietly(lock, e);
            throw e;
        }
    }

    /** What the last recovery found. Never null. */
    public RecoveryReport recoveryReport() {
        return report;
    }

    /** Number of live keys. */
    public int size() {
        ensureOpen();
        return keyDir.size();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            channel.close();
        } finally {
            lock.release();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("engine is closed");
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Already unwinding a failed open.
        }
    }

    /**
     * Releases the lock while an open is failing.
     *
     * <p>A failure here is attached to the original error rather than replacing
     * it: "could not release the lock" would otherwise hide "this file is not a
     * bitcask log", which is the fact the caller actually needs.
     */
    private static void releaseQuietly(DirectoryLock lock, Throwable primary) {
        try {
            lock.release();
        } catch (IOException suppressed) {
            primary.addSuppressed(suppressed);
        }
    }
}
