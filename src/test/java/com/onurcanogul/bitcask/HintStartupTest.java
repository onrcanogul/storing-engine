package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.compaction.SegmentStats;
import com.onurcanogul.bitcask.store.HintFile;
import com.onurcanogul.bitcask.store.SegmentFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Startup without reading the log.
 *
 * <p>Replaying a segment reads every record whole in order to learn where the
 * keys are, which makes opening a store cost what it weighs rather than what it
 * indexes. A closed segment with a hint beside it is loaded from the hint
 * instead, and the log is never opened.
 *
 * <p>The index that comes out has to be identical either way, so every test here
 * checks the same two things: that the hint was actually used, and that using it
 * changed nothing about what the store says.
 */
class HintStartupTest {

    @TempDir
    Path dir;

    private static final int KEYS = 30;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] key(int i) {
        return b("key:" + i);
    }

    private BitcaskConfig config() {
        // Small segments, so a handful of writes closes several of them.
        return BitcaskConfig.defaults()
                .withMaxValueSize(64)
                .withMaxSegmentSize(512);
    }

    /** Writes enough to close several segments, leaving a known final state. */
    private void fillAndClose() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int round = 0; round < 4; round++) {
                for (int i = 0; i < KEYS; i++) {
                    db.put(key(i), b("round:" + round));
                }
            }
            for (int i = 0; i < KEYS; i++) {
                db.put(key(i), b("final:" + i));
            }
            db.delete(key(0));
        }
    }

    private void assertContentsAreRight(Bitcask db) throws Exception {
        assertNull(db.get(key(0)), "the deleted key came back");
        for (int i = 1; i < KEYS; i++) {
            assertArrayEquals(b("final:" + i), db.get(key(i)), "key:" + i);
        }
        assertEquals(KEYS - 1, db.size());
    }

    private long closedSegmentCount() throws Exception {
        return SegmentFiles.listFileIds(dir).size() - 1;
    }

    @Test
    void everyClosedSegmentIsLoadedFromItsHint() throws Exception {
        fillAndClose();
        long closed = closedSegmentCount();
        assertTrue(closed > 1, "the fixture did not close enough segments: " + closed);

        try (Bitcask db = Bitcask.open(dir, config())) {
            assertEquals(closed, db.recoveryReport().segmentsLoadedFromHints(),
                    "every closed segment should have been loaded from a hint");
            assertContentsAreRight(db);
        }
    }

    @Test
    void aStoreWithoutHintsStillOpensTheSlowWay() throws Exception {
        fillAndClose();

        for (int fileId : SegmentFiles.listFileIds(dir)) {
            HintFile.delete(dir, fileId);
        }

        try (Bitcask db = Bitcask.open(dir, config())) {
            assertEquals(0, db.recoveryReport().segmentsLoadedFromHints());
            assertContentsAreRight(db);
        }
    }

    @Test
    void aDamagedHintIsIgnoredRatherThanBelieved() throws Exception {
        fillAndClose();
        int firstClosed = SegmentFiles.listFileIds(dir).get(0);

        byte[] hint = Files.readAllBytes(SegmentFiles.hintPathOf(dir, firstClosed));
        hint[hint.length / 2] ^= 0x20;
        Files.write(SegmentFiles.hintPathOf(dir, firstClosed), hint);

        try (Bitcask db = Bitcask.open(dir, config())) {
            assertEquals(closedSegmentCount() - 1, db.recoveryReport().segmentsLoadedFromHints(),
                    "the damaged hint should have been skipped and its segment scanned");
            assertContentsAreRight(db);
        }
    }

    /**
     * The trap in building hints from memory: after a restart the writer knows
     * nothing about what the active segment already holds. A hint written from
     * that half-empty memory would claim to describe the whole segment while
     * listing only part of it.
     *
     * <p>Two separate things keep that from costing anything, and this test holds
     * both. Loading refuses a hint whose entries do not run end to end across the
     * whole segment, so an incomplete one is caught rather than believed — that is
     * what keeps the data right. Seeding the writer with what recovery found is
     * what keeps the hint: without it the segment is still read correctly, only
     * the slow way, and the feature quietly stops applying to every segment a
     * restart happened to land in.
     */
    @Test
    void aSegmentWrittenAcrossARestartGetsACompleteHint() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int i = 0; i < 4; i++) {
                db.put(key(i), b("before-restart"));
            }
        }

        // Reopen and keep writing into the segment that was left active, until it
        // rotates and its hint is written from a half-empty memory.
        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int i = 4; i < KEYS; i++) {
                db.put(key(i), b("after-restart"));
            }
        }

        try (Bitcask db = Bitcask.open(dir, config())) {
            long closed = closedSegmentCount();
            assertTrue(closed > 0, "nothing rotated, so this test proved nothing");
            assertEquals(closed, db.recoveryReport().segmentsLoadedFromHints(),
                    "a segment written across the restart lost its hint: the writer "
                            + "started collecting from empty instead of from what recovery found");

            for (int i = 0; i < 4; i++) {
                assertArrayEquals(b("before-restart"), db.get(key(i)),
                        "key:" + i + " was written before the restart and is now gone");
            }
            for (int i = 4; i < KEYS; i++) {
                assertArrayEquals(b("after-restart"), db.get(key(i)), "key:" + i);
            }
            assertEquals(KEYS, db.size());
        }
    }

    /**
     * The claim that makes hints safe to use at all: a hinted startup and a
     * scanned one are the same startup.
     *
     * <p>Not just the same keys. Garbage accounting is rebuilt by noticing which
     * records the replay supersedes, and it decides what compaction does next —
     * so a hinted open that counted differently would leave the store making
     * different decisions depending on how it happened to be opened.
     */
    @Test
    void aHintedStartupAgreesWithAScannedOneDownToTheGarbageCounters() throws Exception {
        fillAndClose();

        List<SegmentStats> fromHints;
        long recordsFromHints;
        try (Bitcask db = Bitcask.open(dir, config())) {
            assertTrue(db.recoveryReport().segmentsLoadedFromHints() > 0, "no hint was used");
            fromHints = db.compactionStats().segments();
            recordsFromHints = db.recoveryReport().recordsReplayed();
        }

        for (int fileId : SegmentFiles.listFileIds(dir)) {
            HintFile.delete(dir, fileId);
        }

        try (Bitcask db = Bitcask.open(dir, config())) {
            assertEquals(0, db.recoveryReport().segmentsLoadedFromHints());
            assertEquals(recordsFromHints, db.recoveryReport().recordsReplayed(),
                    "the two startups disagree about how many records there are");
            assertEquals(fromHints, db.compactionStats().segments(),
                    "the two startups disagree about what is garbage");
        }
    }

    /**
     * Hints are built by a background writer, so at the moment {@code close()} is
     * called there may be a backlog of them still unwritten. A clean shutdown has
     * to finish that work.
     *
     * <p>Losing it would cost nothing but speed — the next startup would read
     * those logs — which is exactly why it would go unnoticed without a test
     * saying so. The burst here is what makes the backlog certain: rotations
     * arrive microseconds apart while a hint takes milliseconds to write.
     */
    @Test
    void aBacklogOfHintsIsWrittenBeforeCloseReturns() throws Exception {
        try (Bitcask db = Bitcask.open(dir, config())) {
            for (int i = 0; i < 500; i++) {
                db.put(key(i), b("v:" + i));
            }
        }

        List<Integer> fileIds = SegmentFiles.listFileIds(dir);
        int active = fileIds.get(fileIds.size() - 1);
        assertTrue(fileIds.size() > 20, "not enough rotations to build a backlog: " + fileIds.size());

        for (int fileId : fileIds) {
            if (fileId == active) {
                continue;
            }
            assertTrue(Files.isRegularFile(SegmentFiles.hintPathOf(dir, fileId)),
                    "segment " + fileId + " closed without its hint being written: "
                            + "close() returned while the writer still had a backlog");
        }
    }

    @Test
    void aTemporaryHintLeftBehindByACrashIsIgnored() throws Exception {
        fillAndClose();

        // A crash between building a hint and moving it into place. The half
        // written file keeps its temporary name, which nothing looks for.
        Files.writeString(SegmentFiles.hintTempPathOf(dir, 1), "not a hint at all");

        try (Bitcask db = Bitcask.open(dir, config())) {
            assertContentsAreRight(db);
        }
    }

    @Test
    void aHintWithNoSegmentBesideItCannotConjureOneUp() throws Exception {
        fillAndClose();
        int highest = SegmentFiles.listFileIds(dir).get(SegmentFiles.listFileIds(dir).size() - 1);

        // Left behind by a merge that died between deleting a segment and its
        // hint. The directory listing decides which segments exist; a hint has
        // no vote.
        Files.copy(SegmentFiles.hintPathOf(dir, 1), SegmentFiles.hintPathOf(dir, highest + 5));

        try (Bitcask db = Bitcask.open(dir, config())) {
            assertContentsAreRight(db);
        }
    }

    @Test
    void aMergedAwaySegmentTakesItsHintWithIt() throws Exception {
        fillAndClose();

        try (Bitcask db = Bitcask.open(dir, config())) {
            assertTrue(db.merge().didAnything(), "nothing was merged; the test proved nothing");
        }

        List<Integer> remaining = SegmentFiles.listFileIds(dir);
        try (var listing = Files.list(dir)) {
            listing.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("hint-"))
                    .forEach(name -> {
                        int fileId = Integer.parseInt(name.substring(5, name.indexOf('.')));
                        assertTrue(remaining.contains(fileId),
                                "hint " + name + " outlived the segment it describes");
                    });
        }

        try (Bitcask db = Bitcask.open(dir, config())) {
            assertContentsAreRight(db);
        }
    }
}
