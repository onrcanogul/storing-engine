package com.onurcanogul.bitcask.format;

/**
 * A decoded log record.
 *
 * <p>{@code seq} is the sole ordering authority: it answers "which write came
 * later" with certainty, which is the only question recovery and compaction
 * need. {@code tstamp} exists for debugging and possible future TTL support and
 * is <strong>never compared</strong> in any code path, because wall-clock time
 * can move backwards.
 *
 * <p>Arrays are not copied here; this type is internal and its callers own them.
 */
public record LogRecord(long seq, long tstamp, RecordType type, byte[] key, byte[] value) {
}
