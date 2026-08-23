package com.onurcanogul.bitcask.recovery;

import java.util.Map;

/**
 * The outcome of a replay: the report the caller sees, plus the state the engine
 * needs in order to resume writing.
 *
 * @param report        what happened, for the application
 * @param activeFileId  segment the next write belongs in
 * @param writePos      offset within that segment
 * @param maxSeq        highest sequence number seen, so the counter can continue
 * @param deadBytes     bytes per segment held by records the replay superseded,
 *                      so garbage accounting resumes rather than restarting at zero
 */
public record RecoveryResult(RecoveryReport report,
                             int activeFileId,
                             long writePos,
                             long maxSeq,
                             Map<Integer, Long> deadBytes) {
}
