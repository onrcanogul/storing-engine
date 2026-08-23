package com.onurcanogul.bitcask.compaction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionStatsTest {

    private static SegmentStats segment(int fileId, long total, long dead, boolean active) {
        return new SegmentStats(fileId, total, dead, active);
    }

    @Test
    void deadRatioIsDeadOverTotal() {
        assertEquals(0.25, segment(1, 400, 100, false).deadRatio(), 1e-9);
    }

    @Test
    void anEmptySegmentHasNoDeadRatio() {
        assertEquals(0.0, segment(1, 0, 0, false).deadRatio(), 1e-9);
    }

    @Test
    void totalsAreSummedAcrossSegments() {
        CompactionStats stats = new CompactionStats(List.of(
                segment(1, 400, 100, false),
                segment(2, 600, 300, false),
                segment(3, 200, 0, true)));

        assertEquals(1200, stats.totalBytes());
        assertEquals(400, stats.deadBytes());
        assertEquals(400.0 / 1200, stats.deadRatio(), 1e-9);
    }

    @Test
    void theActiveSegmentIsNeverAMergeCandidate() {
        // It is being written to; reading it for a merge would race the writer.
        CompactionStats stats = new CompactionStats(List.of(
                segment(1, 100, 90, false),
                segment(2, 100, 99, true)));

        assertEquals(List.of(1), stats.mergeCandidates(0.5).stream().map(SegmentStats::fileId).toList());
    }

    @Test
    void onlySegmentsAtOrAboveTheThresholdAreCandidates() {
        CompactionStats stats = new CompactionStats(List.of(
                segment(1, 100, 20, false),
                segment(2, 100, 50, false),
                segment(3, 100, 80, false)));

        assertEquals(List.of(2, 3),
                stats.mergeCandidates(0.5).stream().map(SegmentStats::fileId).toList());
    }

    @Test
    void candidatesComeBackInFileIdOrder() {
        CompactionStats stats = new CompactionStats(List.of(
                segment(3, 100, 90, false),
                segment(1, 100, 90, false),
                segment(2, 100, 90, false)));

        assertEquals(List.of(1, 2, 3),
                stats.mergeCandidates(0.5).stream().map(SegmentStats::fileId).toList());
    }

    @Test
    void aStoreWithNothingWrittenReportsNoGarbage() {
        CompactionStats stats = new CompactionStats(List.of(segment(1, 8, 0, true)));

        assertEquals(0.0, stats.deadRatio(), 1e-9);
        assertTrue(stats.mergeCandidates(0.0).isEmpty());
    }
}
