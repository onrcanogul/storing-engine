package com.onurcanogul.bitcask.compaction;

import java.util.Comparator;
import java.util.List;

/**
 * A snapshot of how much garbage the store is carrying.
 *
 * <p>Counted incrementally as writes happen rather than computed on demand:
 * scanning the index would cost one pass over every key, which is the one thing
 * this engine has a lot of.
 *
 * <p>Only superseded PUT records count as dead. A tombstone still has work to do
 * — it cancels an earlier PUT during replay — so it is not garbage yet.
 */
public record CompactionStats(List<SegmentStats> segments) {

    public CompactionStats {
        segments = List.copyOf(segments);
    }

    public long totalBytes() {
        return segments.stream().mapToLong(SegmentStats::totalBytes).sum();
    }

    public long deadBytes() {
        return segments.stream().mapToLong(SegmentStats::deadBytes).sum();
    }

    /** Fraction of the whole store that a full merge could reclaim. */
    public double deadRatio() {
        long total = totalBytes();
        return total == 0 ? 0.0 : (double) deadBytes() / total;
    }

    /**
     * Closed segments carrying at least {@code threshold} garbage, in file id
     * order.
     *
     * <p>The active segment is never a candidate: it is still being written, so
     * a merge reading it would race the writer.
     */
    public List<SegmentStats> mergeCandidates(double threshold) {
        return segments.stream()
                .filter(segment -> !segment.active())
                .filter(segment -> segment.totalBytes() > 0)
                .filter(segment -> segment.deadRatio() >= threshold)
                .filter(segment -> segment.deadBytes() > 0)
                .sorted(Comparator.comparingInt(SegmentStats::fileId))
                .toList();
    }
}
