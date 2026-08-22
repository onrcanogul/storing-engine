package com.onurcanogul.bitcask.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentFilesTest {

    @TempDir
    Path dir;

    private void touch(String name) throws IOException {
        Files.createFile(dir.resolve(name));
    }

    @Test
    void fileNamesArePaddedToTenDigits() {
        assertEquals("data-0000000001.log", SegmentFiles.fileName(1));
        assertEquals("data-0000000042.log", SegmentFiles.fileName(42));
        assertEquals("data-2147483647.log", SegmentFiles.fileName(Integer.MAX_VALUE));
    }

    @Test
    void paddingKeepsAlphabeticalOrderMatchingNumericOrder() {
        // The whole point of zero padding: ls, tab-completion and file managers
        // sort as strings, and must not disagree with the real order.
        List<String> names = List.of(
                SegmentFiles.fileName(1),
                SegmentFiles.fileName(2),
                SegmentFiles.fileName(10),
                SegmentFiles.fileName(100));

        assertEquals(names, names.stream().sorted().toList());
    }

    @Test
    void aFileIdRoundTripsThroughItsName() {
        for (int fileId : new int[] {1, 7, 99, 1_000, Integer.MAX_VALUE}) {
            assertEquals(OptionalInt.of(fileId),
                    SegmentFiles.parseFileId(SegmentFiles.fileName(fileId)));
        }
    }

    @Test
    void unrelatedFileNamesAreNotSegments() {
        assertFalse(SegmentFiles.parseFileId("data.log").isPresent());
        assertFalse(SegmentFiles.parseFileId("bitcask.lock").isPresent());
        assertFalse(SegmentFiles.parseFileId("data-1.log").isPresent());
        assertFalse(SegmentFiles.parseFileId("data-0000000001.log.tmp").isPresent());
        assertFalse(SegmentFiles.parseFileId("notes.txt").isPresent());
    }

    @Test
    void listReturnsFileIdsInNumericOrder() throws Exception {
        touch(SegmentFiles.fileName(10));
        touch(SegmentFiles.fileName(2));
        touch(SegmentFiles.fileName(1));
        touch("bitcask.lock");
        touch("unrelated.txt");

        assertEquals(List.of(1, 2, 10), SegmentFiles.listFileIds(dir));
    }

    @Test
    void anEmptyDirectoryHasNoSegments() throws Exception {
        assertEquals(List.of(), SegmentFiles.listFileIds(dir));
    }

    @Test
    void theActiveSegmentIsTheHighestNumberedOne() throws Exception {
        touch(SegmentFiles.fileName(3));
        touch(SegmentFiles.fileName(1));

        assertEquals(OptionalInt.of(3), SegmentFiles.activeFileId(dir));
    }

    @Test
    void anEmptyDirectoryHasNoActiveSegment() throws Exception {
        assertFalse(SegmentFiles.activeFileId(dir).isPresent());
    }

    @Test
    void aLegacyLogIsAdoptedAsTheFirstSegment() throws Exception {
        Files.writeString(dir.resolve("data.log"), "phase one data");

        SegmentFiles.adoptLegacyLog(dir);

        assertFalse(Files.exists(dir.resolve("data.log")));
        assertEquals("phase one data", Files.readString(dir.resolve(SegmentFiles.fileName(1))));
    }

    @Test
    void adoptingIsANoOpWhenThereIsNoLegacyLog() throws Exception {
        touch(SegmentFiles.fileName(1));

        SegmentFiles.adoptLegacyLog(dir);

        assertEquals(List.of(1), SegmentFiles.listFileIds(dir));
    }

    @Test
    void aLegacyLogAlongsideRealSegmentsIsRefused() throws Exception {
        // Ambiguous: we cannot know where the legacy file belongs in the order.
        // Guessing would risk silently reordering or losing data.
        Files.writeString(dir.resolve("data.log"), "phase one data");
        touch(SegmentFiles.fileName(1));

        assertThrows(IOException.class, () -> SegmentFiles.adoptLegacyLog(dir));

        assertTrue(Files.exists(dir.resolve("data.log")), "the refusal must leave both files intact");
    }
}
