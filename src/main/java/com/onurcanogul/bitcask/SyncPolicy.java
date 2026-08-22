package com.onurcanogul.bitcask;

/**
 * When the engine forces data out of the OS page cache and onto the disk.
 *
 * <p>{@code NEVER} survives {@code kill -9} because the page cache outlives the
 * process; it does not survive power loss. {@code ALWAYS} survives both, at
 * roughly 50-100x the cost per write.
 */
public enum SyncPolicy {

    /** Let the operating system flush on its own schedule. */
    NEVER,

    /** fsync on every write. */
    ALWAYS
}
