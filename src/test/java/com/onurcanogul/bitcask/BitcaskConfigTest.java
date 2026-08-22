package com.onurcanogul.bitcask;

import com.onurcanogul.bitcask.recovery.RecoveryMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BitcaskConfigTest {

    @Test
    void defaultsMatchTheSpec() {
        BitcaskConfig c = BitcaskConfig.defaults();
        assertEquals(16 * 1024 * 1024, c.maxValueSize());
        assertEquals(SyncPolicy.NEVER, c.syncPolicy());
        assertEquals(RecoveryMode.TOLERATE_TAIL, c.recoveryMode());
    }

    @Test
    void maxValueSizeCannotExceedHardLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> new BitcaskConfig(65 * 1024 * 1024, SyncPolicy.NEVER, RecoveryMode.TOLERATE_TAIL));
    }

    @Test
    void maxValueSizeMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new BitcaskConfig(0, SyncPolicy.NEVER, RecoveryMode.TOLERATE_TAIL));
    }
}
