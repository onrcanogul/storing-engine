package com.onurcanogul.bitcask.store;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileSystemException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenFileLimitTest {

    @Test
    void aDescriptorExhaustionErrorIsExplained() {
        IOException raw = new IOException("Too many open files");

        IOException described = OpenFileLimit.describe(raw, 1_024);

        assertTrue(described.getMessage().contains("1024 segment"),
                "the message should say how many segments are open: " + described.getMessage());
        assertTrue(described.getMessage().toLowerCase().contains("ulimit"),
                "the message should say what to do about it: " + described.getMessage());
        assertSame(raw, described.getCause(), "the original error must remain the cause");
    }

    @Test
    void theSameExplanationCoversFileSystemException() {
        IOException raw = new FileSystemException("/data/seg", null, "Too many open files");

        IOException described = OpenFileLimit.describe(raw, 7);

        assertTrue(described.getMessage().contains("7 segment"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        IOException raw = new IOException("too many open files in system");

        assertTrue(OpenFileLimit.describe(raw, 3).getMessage().contains("3 segment"));
    }

    @Test
    void unrelatedErrorsArePassedThroughUntouched() {
        IOException raw = new IOException("No space left on device");

        assertSame(raw, OpenFileLimit.describe(raw, 5),
                "an unrelated error must not be rewrapped or reworded");
    }

    @Test
    void anErrorWithoutAMessageIsPassedThrough() {
        IOException raw = new IOException();

        assertSame(raw, OpenFileLimit.describe(raw, 5));
    }
}
