package com.onurcanogul.bitcask.format;

/**
 * Limits imposed by the on-disk format itself.
 *
 * <p>These are constants, never configuration. A parser that trusted a
 * configurable bound would let a lowered setting reclassify already-written
 * records as corrupt.
 *
 * <p>Internal to the engine.
 */
public final class FormatLimits {

    /** Widest key the 2-byte {@code keyLen} field can express. */
    public static final int MAX_KEY_SIZE = 65_535;

    /**
     * Largest value length a record is allowed to claim while being parsed.
     *
     * <p>Separate from {@code BitcaskConfig.maxValueSize}, which only governs
     * what {@code put} accepts.
     */
    public static final int HARD_MAX_VALUE_SIZE = 64 * 1024 * 1024;

    private FormatLimits() {
    }
}
