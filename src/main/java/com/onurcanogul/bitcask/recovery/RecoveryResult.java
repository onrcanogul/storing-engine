package com.onurcanogul.bitcask.recovery;

/**
 * The outcome of a replay: the report the caller sees, plus the state the engine
 * needs in order to resume writing.
 *
 * @param report    what happened, for the application
 * @param endOffset where the next write belongs
 * @param maxSeq    highest sequence number seen, so the counter can continue
 */
public record RecoveryResult(RecoveryReport report, long endOffset, long maxSeq) {
}
