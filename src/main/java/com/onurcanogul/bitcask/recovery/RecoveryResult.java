package com.onurcanogul.bitcask.recovery;

import com.onurcanogul.bitcask.store.HintFile;

import java.util.List;
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
 * @param activeSegmentEntries every record found in the active segment, so the
 *                      engine can go on collecting hint entries where the
 *                      previous run left off. Without them the hint written when
 *                      that segment finally rotates would describe only what this
 *                      run appended, and everything written before the restart
 *                      would vanish at the next startup.
 */
public record RecoveryResult(RecoveryReport report,
                             int activeFileId,
                             long writePos,
                             long maxSeq,
                             Map<Integer, Long> deadBytes,
                             List<HintFile.Entry> activeSegmentEntries) {
}
