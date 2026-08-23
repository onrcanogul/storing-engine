package com.onurcanogul.bitcask.recovery;

/**
 * What recovery found while rebuilding the index.
 *
 * <p>Recovery returns this rather than only writing to a log, because
 * {@code bytesDiscarded > 0} means data was lost and only the application can
 * decide what that means for it. The engine's obligation is to say so plainly
 * instead of resolving it on the application's behalf.
 *
 * @param recordsReplayed   valid records applied to the index, whether they came
 *                          from a log or from a hint file
 * @param liveKeys          keys present once the replay finished
 * @param bytesDiscarded    bytes truncated from a damaged tail; 0 on a clean open
 * @param truncatedAtOffset offset where truncation happened, or -1 if none
 * @param reason            why the scan stopped
 * @param segmentsLoadedFromHints closed segments whose index came from a hint
 *                          file rather than from reading the log; the rest were
 *                          scanned in full
 */
public record RecoveryReport(long recordsReplayed,
                             int liveKeys,
                             long bytesDiscarded,
                             long truncatedAtOffset,
                             StopReason reason,
                             long segmentsLoadedFromHints) {

    /** True when the log was damaged and part of it could not be recovered. */
    public boolean lostData() {
        return bytesDiscarded > 0;
    }
}
