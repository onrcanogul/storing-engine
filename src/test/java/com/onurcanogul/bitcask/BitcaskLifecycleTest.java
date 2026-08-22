package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.store.SegmentFiles;
import com.onurcanogul.bitcask.recovery.StopReason;
import com.onurcanogul.bitcask.recovery.RecoveryReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitcaskLifecycleTest {

    @TempDir
    Path dir;

    @Test
    void opensAnEmptyDirectoryAndCreatesTheLog() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertEquals(0, db.size());
            assertTrue(Files.exists(SegmentFiles.pathOf(dir, 1)));

            RecoveryReport report = db.recoveryReport();
            assertEquals(0, report.recordsReplayed());
            assertEquals(0, report.bytesDiscarded());
            assertFalse(report.lostData());
            assertEquals(StopReason.CLEAN_EOF, report.reason());
        }
    }

    @Test
    void freshLogStartsWithJustTheFileHeader() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertEquals(8, Files.size(SegmentFiles.pathOf(dir, 1)));
        }
    }

    @Test
    void openCreatesTheDirectoryIfMissing() throws Exception {
        Path nested = dir.resolve("does/not/exist/yet");
        try (Bitcask db = Bitcask.open(nested, BitcaskConfig.defaults())) {
            assertTrue(Files.isDirectory(nested));
        }
    }

    @Test
    void secondOpenOfTheSameDirectoryFails() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertThrows(IOException.class, () -> Bitcask.open(dir, BitcaskConfig.defaults()));
        }
    }

    @Test
    void closeIsIdempotent() throws Exception {
        Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults());
        db.close();
        db.close();
    }

    @Test
    void operationsOnAClosedEngineThrow() throws Exception {
        Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults());
        db.close();

        assertThrows(IllegalStateException.class, db::size);
    }

    @Test
    void reopenAfterCloseSucceeds() throws Exception {
        Bitcask.open(dir, BitcaskConfig.defaults()).close();
        Bitcask.open(dir, BitcaskConfig.defaults()).close();
    }

    @Test
    void openFailsOnAForeignSegmentAndReleasesTheLock() throws Exception {
        Files.write(SegmentFiles.pathOf(dir, 1), "this is not a bitcask segment".getBytes());

        assertThrows(IOException.class, () -> Bitcask.open(dir, BitcaskConfig.defaults()));

        // The failed open must not have left the directory locked.
        Files.delete(SegmentFiles.pathOf(dir, 1));
        Bitcask.open(dir, BitcaskConfig.defaults()).close();
    }
}
