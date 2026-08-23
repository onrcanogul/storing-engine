package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.format.FileHeader;
import com.onurcanogul.bitcask.format.RecordCodec;
import com.onurcanogul.bitcask.recovery.RecoveryMode;

/**
 * Engine configuration.
 *
 * @param maxValueSize   largest value {@code put} accepts
 * @param syncPolicy     when to fsync
 * @param recoveryMode   what to do with a damaged log
 * @param maxSegmentSize size at which the active segment is rotated
 */
public record BitcaskConfig(int maxValueSize,
                            SyncPolicy syncPolicy,
                            RecoveryMode recoveryMode,
                            int maxSegmentSize,
                            double mergeThreshold) {

    public static final int DEFAULT_MAX_VALUE_SIZE = 16 * 1024 * 1024;

    public static final int DEFAULT_MAX_SEGMENT_SIZE = 128 * 1024 * 1024;

    /**
     * Fraction of a segment that must be garbage before merging it is worth the
     * read-and-rewrite it costs. At one half, a merge rewrites half of what it
     * reads and reclaims the other half.
     */
    public static final double DEFAULT_MERGE_THRESHOLD = 0.5;

    public BitcaskConfig {
        if (maxValueSize <= 0) {
            throw new IllegalArgumentException("maxValueSize must be positive: " + maxValueSize);
        }
        if (maxValueSize > com.onurcanogul.bitcask.format.FormatLimits.HARD_MAX_VALUE_SIZE) {
            throw new IllegalArgumentException(
                    "maxValueSize " + maxValueSize + " exceeds HARD_MAX_VALUE_SIZE "
                            + com.onurcanogul.bitcask.format.FormatLimits.HARD_MAX_VALUE_SIZE);
        }
        if (maxSegmentSize <= 0) {
            throw new IllegalArgumentException("maxSegmentSize must be positive: " + maxSegmentSize);
        }

        if (mergeThreshold < 0.0 || mergeThreshold > 1.0) {
            throw new IllegalArgumentException("mergeThreshold must be between 0 and 1: " + mergeThreshold);
        }

        int required = smallestUsableSegmentSize(maxValueSize);
        if (maxSegmentSize < required) {
            throw new IllegalArgumentException(
                    "maxSegmentSize " + maxSegmentSize + " cannot hold even a minimal record; "
                            + "at least " + required + " is required for maxValueSize " + maxValueSize);
        }
    }

    /**
     * The smallest segment that could hold any record at all: one with a
     * single-byte key and a value at the configured limit.
     *
     * <p>This deliberately assumes the <em>smallest</em> possible key rather than
     * the largest. Configuration cannot know how large keys will actually be, and
     * assuming the 64 KB ceiling would force every store to use 65 KB segments no
     * matter what it stores.
     *
     * <p>The real question — does <em>this</em> record fit anywhere — is answered
     * at write time, where the actual size is known. A record is never split
     * across segments, so one that fits nowhere is rejected outright instead of
     * sending rotation into a loop.
     */
    public static int smallestUsableSegmentSize(int maxValueSize) {
        return FileHeader.SIZE
                + RecordCodec.HEADER_SIZE
                + 1
                + maxValueSize;
    }

    public static BitcaskConfig defaults() {
        return new BitcaskConfig(
                DEFAULT_MAX_VALUE_SIZE,
                SyncPolicy.NEVER,
                RecoveryMode.TOLERATE_TAIL,
                DEFAULT_MAX_SEGMENT_SIZE,
                DEFAULT_MERGE_THRESHOLD);
    }

    public BitcaskConfig withMaxValueSize(int newMaxValueSize) {
        return new BitcaskConfig(newMaxValueSize, syncPolicy, recoveryMode, maxSegmentSize, mergeThreshold);
    }

    public BitcaskConfig withSyncPolicy(SyncPolicy newSyncPolicy) {
        return new BitcaskConfig(maxValueSize, newSyncPolicy, recoveryMode, maxSegmentSize, mergeThreshold);
    }

    public BitcaskConfig withRecoveryMode(RecoveryMode newRecoveryMode) {
        return new BitcaskConfig(maxValueSize, syncPolicy, newRecoveryMode, maxSegmentSize, mergeThreshold);
    }

    public BitcaskConfig withMaxSegmentSize(int newMaxSegmentSize) {
        return new BitcaskConfig(maxValueSize, syncPolicy, recoveryMode, newMaxSegmentSize, mergeThreshold);
    }

    public BitcaskConfig withMergeThreshold(double newMergeThreshold) {
        return new BitcaskConfig(maxValueSize, syncPolicy, recoveryMode, maxSegmentSize, newMergeThreshold);
    }
}
