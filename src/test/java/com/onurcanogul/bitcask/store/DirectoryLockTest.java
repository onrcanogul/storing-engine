package com.onurcanogul.bitcask.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryLockTest {

    @TempDir
    Path dir;

    @Test
    void secondAcquireInTheSameJvmFails() throws Exception {
        DirectoryLock first = DirectoryLock.acquire(dir);
        try {
            assertThrows(IOException.class, () -> DirectoryLock.acquire(dir));
        } finally {
            first.release();
        }
    }

    @Test
    void lockCanBeReacquiredAfterRelease() throws Exception {
        DirectoryLock first = DirectoryLock.acquire(dir);
        first.release();

        DirectoryLock second = DirectoryLock.acquire(dir);
        second.release();
    }

    @Test
    void releaseIsIdempotent() throws Exception {
        DirectoryLock lock = DirectoryLock.acquire(dir);
        lock.release();
        lock.release();
    }

    @Test
    void acquireCreatesTheLockFile() throws Exception {
        try (DirectoryLock lock = DirectoryLock.acquire(dir)) {
            assertTrue(Files.exists(dir.resolve("bitcask.lock")));
        }
    }

    @Test
    void aFailedAcquireDoesNotPoisonTheRegistry() throws Exception {
        DirectoryLock first = DirectoryLock.acquire(dir);
        assertThrows(IOException.class, () -> DirectoryLock.acquire(dir));
        first.release();

        // The failed attempt must not have left the directory marked as taken.
        DirectoryLock again = DirectoryLock.acquire(dir);
        again.release();
    }

    @Test
    void twoNamesForTheSameDirectoryCollide() throws Exception {
        // A path that reaches the same directory by a different route must not
        // be able to take a second lock.
        Path viaDot = dir.resolve(".").normalize();
        Path viaParent = dir.resolve("sub").resolve("..").normalize();
        Files.createDirectories(dir.resolve("sub"));

        DirectoryLock first = DirectoryLock.acquire(viaDot);
        try {
            assertThrows(IOException.class, () -> DirectoryLock.acquire(viaParent));
        } finally {
            first.release();
        }
    }
}
