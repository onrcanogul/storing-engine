package com.onurcanogul.bitcask.format;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * The 8-byte signature at the start of every log file.
 *
 * <pre>
 *   offset  size  field
 *        0     4  magic     "BCSK"
 *        4     2  version   format version
 *        6     2  reserved  zero, room for future flags
 * </pre>
 *
 * <p>The magic lets the engine reject a foreign file with a clear error instead
 * of interpreting arbitrary bytes as records. The version field costs two bytes
 * today and is the only thing that makes a future format change survivable.
 *
 * <p>Internal to the engine.
 */
public final class FileHeader {

    public static final int SIZE = 8;

    /** ASCII "BCSK". */
    public static final int MAGIC = 0x4243534B;

    public static final short VERSION = 1;

    private FileHeader() {
    }

    /** Writes the header at offset 0. */
    public static void write(FileChannel channel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(MAGIC);
        buf.putShort(VERSION);
        buf.putShort((short) 0);
        buf.flip();

        long position = 0;
        while (buf.hasRemaining()) {
            position += channel.write(buf, position);
        }
    }

    /** @throws IOException if the file is not ours, or is a version we cannot read */
    public static void validate(FileChannel channel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN);

        long position = 0;
        while (buf.hasRemaining()) {
            int read = channel.read(buf, position);
            if (read < 0) {
                break;
            }
            position += read;
        }

        if (buf.hasRemaining()) {
            throw new IOException("file header truncated: only " + position + " bytes");
        }

        buf.flip();
        int magic = buf.getInt();
        short version = buf.getShort();

        if (magic != MAGIC) {
            throw new IOException(String.format("not a bitcask log file: magic=0x%08X", magic));
        }
        if (version != VERSION) {
            throw new IOException("unsupported format version: " + version
                    + " (this build reads version " + VERSION + ")");
        }
    }
}
