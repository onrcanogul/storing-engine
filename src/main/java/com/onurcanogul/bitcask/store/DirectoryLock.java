package com.onurcanogul.bitcask.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the one-writer-per-directory rule.
 *
 * <p>Two writers on the same log would each track their own write position and
 * overwrite each other's records — silent, unrecoverable corruption. This class
 * makes that impossible rather than merely documented.
 *
 * <p>Two guards are needed, because they cover different things. A
 * {@link FileLock} is held by the JVM as a whole: it stops other processes, but
 * a second lock attempt inside this JVM throws
 * {@link OverlappingFileLockException} rather than failing cleanly. The
 * in-process registry turns that case into the same clear error.
 *
 * <p>Internal to the engine.
 */
public final class DirectoryLock implements AutoCloseable {

    public static final String LOCK_FILE_NAME = "bitcask.lock";

    private static final Set<Path> OPEN_DIRECTORIES = ConcurrentHashMap.newKeySet();

    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;

    private volatile boolean released;

    private DirectoryLock(Path directory, FileChannel channel, FileLock lock) {
        this.directory = directory;
        this.channel = channel;
        this.lock = lock;
    }

    /** @throws IOException if the directory is already open here or in another process */
    public static DirectoryLock acquire(Path dir) throws IOException {
        // Resolve symlinks and relative steps so two names for one directory collide.
        Path directory = dir.toRealPath();

        if (!OPEN_DIRECTORIES.add(directory)) {
            throw new IOException("directory is already open in this JVM: " + directory);
        }

        FileChannel channel = null;
        try {
            channel = FileChannel.open(directory.resolve(LOCK_FILE_NAME),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);

            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("directory is locked by another process: " + directory);
            }
            return new DirectoryLock(directory, channel, lock);

        } catch (OverlappingFileLockException e) {
            cleanUp(directory, channel);
            throw new IOException("directory is locked by this JVM: " + directory, e);
        } catch (IOException e) {
            cleanUp(directory, channel);
            throw e;
        } catch (RuntimeException e) {
            cleanUp(directory, channel);
            throw e;
        }
    }

    public void release() throws IOException {
        if (released) {
            return;
        }
        released = true;
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } finally {
            OPEN_DIRECTORIES.remove(directory);
            channel.close();
        }
    }

    @Override
    public void close() throws IOException {
        release();
    }

    /**
     * Undoes a partial acquire.
     *
     * <p>Without removing the registry entry, a failed attempt would leave the
     * directory unopenable for the rest of the JVM's life.
     */
    private static void cleanUp(Path directory, FileChannel channel) {
        OPEN_DIRECTORIES.remove(directory);
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Already unwinding a failed acquire; nothing useful to do.
        }
    }
}
