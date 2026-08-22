package com.onurcanogul.bitcask.index;

/**
 * Where a key's most recent record lives.
 *
 * <p>Points at the whole record rather than just the value, so every read can
 * verify the CRC — which covers the entire record. The few extra bytes come
 * from a page that is being read anyway.
 *
 * @param fileId     always 0 in Phase 1; the field exists for segment rotation
 * @param recordPos  offset of the record in the log
 * @param recordSize total size of the record
 * @param seq        the record's sequence number
 */
public record KeyDirEntry(int fileId, long recordPos, int recordSize, long seq) {
}
