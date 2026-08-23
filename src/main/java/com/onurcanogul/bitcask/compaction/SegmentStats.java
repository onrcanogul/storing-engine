package com.onurcanogul.bitcask.compaction;

/**
 * How much of one segment is still worth keeping.
 *
 * @param fileId     the segment
 * @param totalBytes its size on disk
 * @param deadBytes  bytes held by records that have since been superseded
 * @param active     whether writes are still landing in it
 */
public record SegmentStats(int fileId, long totalBytes, long deadBytes, boolean active) {

    /** Fraction of the segment that a merge could reclaim, between 0 and 1. */
    public double deadRatio() {
        return totalBytes == 0 ? 0.0 : (double) deadBytes / totalBytes;
    }
}
