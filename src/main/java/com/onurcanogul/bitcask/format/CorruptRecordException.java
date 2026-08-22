package com.onurcanogul.bitcask.format;

import java.io.IOException;

/** Thrown when bytes on disk cannot be trusted as a record. */
public class CorruptRecordException extends IOException {

    public CorruptRecordException(String message) {
        super(message);
    }
}
