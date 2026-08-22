package com.onurcanogul.bitcask.format;

/**
 * The kind of a log record.
 *
 * <p>Deletion gets its own field rather than being encoded as a zero-length or
 * negative value length: an empty value is a legitimate thing to store, and
 * overloading a length field would collide with unsigned reads.
 */
public enum RecordType {

    PUT((byte) 1),
    TOMBSTONE((byte) 2);

    private final byte code;

    RecordType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static RecordType fromCode(byte code) throws CorruptRecordException {
        for (RecordType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new CorruptRecordException("unknown record type: " + code);
    }
}
