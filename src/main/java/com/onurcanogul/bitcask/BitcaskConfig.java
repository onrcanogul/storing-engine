package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.format.FormatLimits;

/**
 * Engine configuration.
 *
 * @param maxValueSize  largest value {@code put} accepts
 * @param syncPolicy    when to fsync
 * @param recoveryMode  what to do with a damaged log
 */
public record BitcaskConfig(int maxValueSize, SyncPolicy syncPolicy, RecoveryMode recoveryMode) {

    public static final int DEFAULT_MAX_VALUE_SIZE = 16 * 1024 * 1024;

    public BitcaskConfig {
        if (maxValueSize <= 0) {
            throw new IllegalArgumentException("maxValueSize must be positive: " + maxValueSize);
        }
        if (maxValueSize > FormatLimits.HARD_MAX_VALUE_SIZE) {
            throw new IllegalArgumentException(
                    "maxValueSize " + maxValueSize + " exceeds HARD_MAX_VALUE_SIZE " + FormatLimits.HARD_MAX_VALUE_SIZE);
        }
    }

    public static BitcaskConfig defaults() {
        return new BitcaskConfig(DEFAULT_MAX_VALUE_SIZE, SyncPolicy.NEVER, RecoveryMode.TOLERATE_TAIL);
    }
}
