package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.format.FileHeader;
import com.onurcanogul.bitcask.format.FormatLimits;
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
                            int maxSegmentSize) {

    public static final int DEFAULT_MAX_VALUE_SIZE = 16 * 1024 * 1024;

    public static final int DEFAULT_MAX_SEGMENT_SIZE = 128 * 1024 * 1024;

    public BitcaskConfig {
        if (maxValueSize <= 0) {
            throw new IllegalArgumentException("maxValueSize must be positive: " + maxValueSize);
        }
        if (maxValueSize > FormatLimits.HARD_MAX_VALUE_SIZE) {
            throw new IllegalArgumentException(
                    "maxValueSize " + maxValueSize + " exceeds HARD_MAX_VALUE_SIZE "
                            + FormatLimits.HARD_MAX_VALUE_SIZE);
        }
        if (maxSegmentSize <= 0) {
            throw new IllegalArgumentException("maxSegmentSize must be positive: " + maxSegmentSize);
        }

        int required = smallestUsableSegmentSize(maxValueSize);
        if (maxSegmentSize < required) {
            throw new IllegalArgumentException(
                    "maxSegmentSize " + maxSegmentSize + " cannot hold the largest possible record; "
                            + "at least " + required + " is required for maxValueSize " + maxValueSize);
        }
    }

    /**
     * The smallest segment that can still hold one record of the largest
     * permitted shape.
     *
     * <p>A record is never split across segments, so one that fits nowhere would
     * make rotation loop forever: rotate, still does not fit, rotate again. This
     * bound turns that into a configuration error at construction time.
     */
    public static int smallestUsableSegmentSize(int maxValueSize) {
        return FileHeader.SIZE
                + RecordCodec.HEADER_SIZE
                + FormatLimits.MAX_KEY_SIZE
                + maxValueSize;
    }

    public static BitcaskConfig defaults() {
        return new BitcaskConfig(
                DEFAULT_MAX_VALUE_SIZE,
                SyncPolicy.NEVER,
                RecoveryMode.TOLERATE_TAIL,
                DEFAULT_MAX_SEGMENT_SIZE);
    }

    public BitcaskConfig withMaxValueSize(int newMaxValueSize) {
        return new BitcaskConfig(newMaxValueSize, syncPolicy, recoveryMode, maxSegmentSize);
    }

    public BitcaskConfig withSyncPolicy(SyncPolicy newSyncPolicy) {
        return new BitcaskConfig(maxValueSize, newSyncPolicy, recoveryMode, maxSegmentSize);
    }

    public BitcaskConfig withRecoveryMode(RecoveryMode newRecoveryMode) {
        return new BitcaskConfig(maxValueSize, syncPolicy, newRecoveryMode, maxSegmentSize);
    }

    public BitcaskConfig withMaxSegmentSize(int newMaxSegmentSize) {
        return new BitcaskConfig(maxValueSize, syncPolicy, recoveryMode, newMaxSegmentSize);
    }
}
