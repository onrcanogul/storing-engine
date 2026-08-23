package com.onurcanogul.bitcask.compaction;

import java.util.List;

/**
 * What a merge did.
 *
 * @param mergedSegments   segments that were read and then removed
 * @param recordsMoved     live records copied forward
 * @param recordsDiscarded records that were already superseded
 * @param bytesReclaimed   disk given back, net of what was rewritten
 */
public record MergeReport(List<Integer> mergedSegments,
                          long recordsMoved,
                          long recordsDiscarded,
                          long bytesReclaimed) {

    public MergeReport {
        mergedSegments = List.copyOf(mergedSegments);
    }

    public static MergeReport nothingToDo() {
        return new MergeReport(List.of(), 0, 0, 0);
    }

    public boolean didAnything() {
        return !mergedSegments.isEmpty();
    }
}
