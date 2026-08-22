package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.recovery.RecoveryMode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BitcaskConfigTest {

    @Test
    void defaultsMatchTheSpec() {
        BitcaskConfig config = BitcaskConfig.defaults();

        assertEquals(16 * 1024 * 1024, config.maxValueSize());
        assertEquals(128 * 1024 * 1024, config.maxSegmentSize());
        assertEquals(SyncPolicy.NEVER, config.syncPolicy());
        assertEquals(RecoveryMode.TOLERATE_TAIL, config.recoveryMode());
    }

    @Test
    void maxValueSizeCannotExceedHardLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> BitcaskConfig.defaults().withMaxValueSize(65 * 1024 * 1024));
    }

    @Test
    void maxValueSizeMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> BitcaskConfig.defaults().withMaxValueSize(0));
    }

    @Test
    void maxSegmentSizeMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> BitcaskConfig.defaults().withMaxSegmentSize(0));
    }

    @Test
    void theLargestPossibleRecordMustFitInAnEmptySegment() {
        // A record that cannot fit anywhere would make rotation loop forever:
        // rotate, still does not fit, rotate again.
        assertThrows(IllegalArgumentException.class,
                () -> BitcaskConfig.defaults()
                        .withMaxValueSize(16 * 1024 * 1024)
                        .withMaxSegmentSize(1024));
    }

    @Test
    void aSegmentJustLargeEnoughIsAccepted() {
        int maxValueSize = 1024;
        int required = BitcaskConfig.smallestUsableSegmentSize(maxValueSize);

        BitcaskConfig config = BitcaskConfig.defaults()
                .withMaxValueSize(maxValueSize)
                .withMaxSegmentSize(required);

        assertEquals(required, config.maxSegmentSize());
    }

    @Test
    void oneByteBelowTheRequiredSegmentSizeIsRejected() {
        int maxValueSize = 1024;
        int required = BitcaskConfig.smallestUsableSegmentSize(maxValueSize);

        assertThrows(IllegalArgumentException.class,
                () -> BitcaskConfig.defaults()
                        .withMaxValueSize(maxValueSize)
                        .withMaxSegmentSize(required - 1));
    }

    @Test
    void withersLeaveTheOtherSettingsAlone() {
        BitcaskConfig config = BitcaskConfig.defaults().withSyncPolicy(SyncPolicy.ALWAYS);

        assertEquals(SyncPolicy.ALWAYS, config.syncPolicy());
        assertEquals(BitcaskConfig.DEFAULT_MAX_VALUE_SIZE, config.maxValueSize());
        assertEquals(BitcaskConfig.DEFAULT_MAX_SEGMENT_SIZE, config.maxSegmentSize());
        assertEquals(RecoveryMode.TOLERATE_TAIL, config.recoveryMode());
    }
}
