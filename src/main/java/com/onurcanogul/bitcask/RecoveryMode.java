package com.onurcanogul.bitcask;

/**
 * What to do when the log turns out to be damaged.
 *
 * <p>A partial record at the end of the log is the normal, expected trace of a
 * crash. Corruption anywhere else is not, and the two cannot be told apart with
 * certainty, so the choice is left to the application.
 */
public enum RecoveryMode {

    /** Truncate the damaged tail, report what was discarded, and open. */
    TOLERATE_TAIL,

    /** Refuse to open. */
    STRICT
}
