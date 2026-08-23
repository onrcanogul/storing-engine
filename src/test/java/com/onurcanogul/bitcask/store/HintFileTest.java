package com.onurcanogul.bitcask.store;

import com.onurcanogul.bitcask.format.RecordType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hint file is a cache, never a source of truth.
 *
 * <p>Everything it says can be recovered by reading the segment it summarises,
 * so the only question these tests ask is whether a hint that cannot be fully
 * trusted is refused. Refusing one costs a slow startup for that segment;
 * believing a wrong one loses data silently. There is no third option, which is
 * why every kind of damage here has the same expected outcome: absent.
 */
class HintFileTest {

    @TempDir
    Path dir;

    private static final int FILE_ID = 7;
    private static final long SEGMENT_BYTES = 4096;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<HintFile.Entry> entries() {
        return List.of(
                new HintFile.Entry(1, RecordType.PUT, 8, 40, b("alpha")),
                new HintFile.Entry(2, RecordType.PUT, 48, 41, b("beta")),
                new HintFile.Entry(3, RecordType.TOMBSTONE, 89, 32, b("alpha")));
    }

    private static void assertSameEntries(List<HintFile.Entry> expected, List<HintFile.Entry> actual) {
        assertEquals(expected.size(), actual.size(), "entry count");
        for (int i = 0; i < expected.size(); i++) {
            HintFile.Entry want = expected.get(i);
            HintFile.Entry got = actual.get(i);
            assertEquals(want.seq(), got.seq(), "seq of entry " + i);
            assertEquals(want.type(), got.type(), "type of entry " + i);
            assertEquals(want.recordPos(), got.recordPos(), "position of entry " + i);
            assertEquals(want.recordSize(), got.recordSize(), "size of entry " + i);
            assertArrayEquals(want.key(), got.key(), "key of entry " + i);
        }
    }

    @Test
    void whatWasWrittenComesBackInOrder() throws Exception {
        HintFile.write(dir, FILE_ID, entries(), SEGMENT_BYTES);

        Optional<List<HintFile.Entry>> read = HintFile.read(dir, FILE_ID, SEGMENT_BYTES);

        assertTrue(read.isPresent(), "a hint just written should be readable");
        assertSameEntries(entries(), read.get());
    }

    @Test
    void aSegmentWithNoHintIsNotAnError() throws Exception {
        assertTrue(HintFile.read(dir, FILE_ID, SEGMENT_BYTES).isEmpty());
    }

    @Test
    void anEmptySegmentsHintIsStillAHint() throws Exception {
        // A segment can close holding nothing. Its hint has to be distinguishable
        // from a missing one, or startup would scan the log to learn the same
        // nothing every time.
        HintFile.write(dir, FILE_ID, List.of(), SEGMENT_BYTES);

        Optional<List<HintFile.Entry>> read = HintFile.read(dir, FILE_ID, SEGMENT_BYTES);

        assertTrue(read.isPresent());
        assertTrue(read.get().isEmpty());
    }

    @Test
    void aHintIsRejectedIfTheSegmentIsNotTheLengthItDescribes() throws Exception {
        HintFile.write(dir, FILE_ID, entries(), SEGMENT_BYTES);

        // The segment grew or shrank since: this hint describes some other state
        // of the file and cannot be told apart from a stale one.
        assertTrue(HintFile.read(dir, FILE_ID, SEGMENT_BYTES + 1).isEmpty());
    }

    @Test
    void aTruncatedHintIsRejected() throws Exception {
        HintFile.write(dir, FILE_ID, entries(), SEGMENT_BYTES);
        Path path = SegmentFiles.hintPathOf(dir, FILE_ID);

        // A crash between creating the file and finishing it. The footer is the
        // last thing written, so a hint without one was never completed.
        byte[] full = Files.readAllBytes(path);
        Files.write(path, java.util.Arrays.copyOf(full, full.length - 6));

        assertTrue(HintFile.read(dir, FILE_ID, SEGMENT_BYTES).isEmpty());
    }

    @Test
    void aFlippedByteIsRejected() throws Exception {
        HintFile.write(dir, FILE_ID, entries(), SEGMENT_BYTES);
        Path path = SegmentFiles.hintPathOf(dir, FILE_ID);

        byte[] full = Files.readAllBytes(path);
        int somewhereInTheEntries = full.length / 2;
        full[somewhereInTheEntries] ^= 0x40;
        Files.write(path, full);

        assertTrue(HintFile.read(dir, FILE_ID, SEGMENT_BYTES).isEmpty());
    }

    @Test
    void aHintBelongingToAnotherSegmentIsRejected() throws Exception {
        HintFile.write(dir, FILE_ID, entries(), SEGMENT_BYTES);

        // Renamed, copied, or restored from the wrong place: the id inside the
        // file is what settles which segment it really describes.
        Files.move(SegmentFiles.hintPathOf(dir, FILE_ID),
                SegmentFiles.hintPathOf(dir, FILE_ID + 1));

        assertTrue(HintFile.read(dir, FILE_ID + 1, SEGMENT_BYTES).isEmpty());
    }

    @Test
    void deletingRemovesTheFileAndTolerateItBeingGone() throws Exception {
        HintFile.write(dir, FILE_ID, entries(), SEGMENT_BYTES);
        assertTrue(Files.exists(SegmentFiles.hintPathOf(dir, FILE_ID)));

        HintFile.delete(dir, FILE_ID);
        assertFalse(Files.exists(SegmentFiles.hintPathOf(dir, FILE_ID)));

        // A merge deleting a segment that never had a hint must not care.
        HintFile.delete(dir, FILE_ID);
    }

    @Test
    void rewritingAHintReplacesTheOldOne() throws Exception {
        HintFile.write(dir, FILE_ID, entries(), SEGMENT_BYTES);

        List<HintFile.Entry> shorter = List.of(new HintFile.Entry(9, RecordType.PUT, 8, 30, b("only")));
        HintFile.write(dir, FILE_ID, shorter, SEGMENT_BYTES);

        assertSameEntries(shorter, HintFile.read(dir, FILE_ID, SEGMENT_BYTES).orElseThrow());
    }
}
