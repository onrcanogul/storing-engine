package com.onurcanogul.bitcask.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileHeaderTest {

    @TempDir
    Path dir;

    private FileChannel openLog() throws IOException {
        return FileChannel.open(dir.resolve("data.log"),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    private void writeRaw(byte[] content) throws IOException {
        try (FileChannel channel = openLog()) {
            channel.write(ByteBuffer.wrap(content), 0);
        }
    }

    @Test
    void headerIsEightBytes() {
        assertEquals(8, FileHeader.SIZE);
    }

    @Test
    void writtenHeaderValidates() throws Exception {
        try (FileChannel channel = openLog()) {
            FileHeader.write(channel);
            FileHeader.validate(channel);
            assertEquals(8, channel.size());
        }
    }

    @Test
    void wrongMagicIsRejected() throws Exception {
        writeRaw(new byte[] {'J', 'U', 'N', 'K', 0, 1, 0, 0});
        try (FileChannel channel = openLog()) {
            assertThrows(IOException.class, () -> FileHeader.validate(channel));
        }
    }

    @Test
    void unknownVersionIsRejected() throws Exception {
        writeRaw(new byte[] {'B', 'C', 'S', 'K', 0, 99, 0, 0});
        try (FileChannel channel = openLog()) {
            assertThrows(IOException.class, () -> FileHeader.validate(channel));
        }
    }

    @Test
    void truncatedHeaderIsRejected() throws Exception {
        writeRaw(new byte[] {'B', 'C'});
        try (FileChannel channel = openLog()) {
            assertThrows(IOException.class, () -> FileHeader.validate(channel));
        }
    }

    @Test
    void emptyFileIsRejected() throws Exception {
        try (FileChannel channel = openLog()) {
            assertThrows(IOException.class, () -> FileHeader.validate(channel));
        }
    }
}
