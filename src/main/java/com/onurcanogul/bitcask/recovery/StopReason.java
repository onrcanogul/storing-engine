package com.onurcanogul.bitcask.recovery;

/** Why the recovery scan stopped reading the log. */
public enum StopReason {

    /** The scan reached the end of the file with every record intact. */
    CLEAN_EOF,

    /** A record's checksum did not match its contents. */
    CRC_MISMATCH,

    /** A header field was impossible: a zero key length, an unknown type, an implausible size. */
    INVALID_HEADER_FIELD,

    /** A sequence number did not advance, which a correct writer cannot produce. */
    NON_INCREASING_SEQ,

    /** The file ended in the middle of a record — the normal trace of a crash. */
    SHORT_READ
}
