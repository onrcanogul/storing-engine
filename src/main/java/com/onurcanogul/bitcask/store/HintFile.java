package com.onurcanogul.bitcask.store;

import com.onurcanogul.bitcask.format.RecordType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32C;

/**
 * A summary of one closed segment: every record in it, minus the values.
 *
 * <p>Startup only ever needed the keys. Replaying a segment reads each record
 * whole — key, value, checksum over all of it — to end up storing a key, an
 * offset, a length and a sequence number. The values are read and thrown away,
 * which makes startup cost what the store weighs rather than what it indexes.
 * A hint file holds exactly what the index needs and nothing else, so a segment
 * that has one is loaded without the log being opened at all.
 *
 * <p><strong>A hint file is a cache, never a source of truth.</strong>
 * Everything in it can be recomputed by reading the segment. So the loading rule
 * is absolute: anything unexpected — a missing file, a short one, a bad
 * checksum, a length that does not match the segment — means the hint is thrown
 * away and the log is read instead. Refusing a good hint costs one slow segment
 * scan; trusting a bad one loses data with no way to notice.
 *
 * <p>Layout:
 * <pre>
 * header : magic(4) | version(2) | fileId(4)
 * entry  : seq(8) | type(1) | recordPos(8) | recordSize(4) | keyLen(2) | key
 * footer : entryCount(4) | segmentBytes(8) | crc32c(4)
 * </pre>
 *
 * <p>The checksum covers everything before it, so a hint is either whole or
 * rejected. Partial recovery would be pointless work for a file that can be
 * rebuilt.
 *
 * <p>That validation is also what lets a hint be written without an fsync:
 * nothing in it has to survive a crash, only to be recognisable as damaged if
 * it does not.
 *
 * <p>Internal to the engine.
 */
public final class HintFile {

    /** 'H','I','N','T'. */
    private static final int MAGIC = 0x48494E54;

    private static final short VERSION = 1;

    private static final int HEADER_SIZE = 10;
    private static final int FOOTER_SIZE = 16;

    /** Fixed part of an entry: everything but the key bytes. */
    private static final int ENTRY_FIXED_SIZE = 23;

    private HintFile() {
    }

    /**
     * One record's worth of index, as it will be replayed at startup.
     *
     * @param seq        sequence number, which decides who wins between duplicates
     * @param type       whether this record stores a value or cancels one
     * @param recordPos  offset of the record within its segment
     * @param recordSize length of the whole record on disk
     * @param key        the key, copied verbatim out of the record
     */
    public record Entry(long seq, RecordType type, long recordPos, int recordSize, byte[] key) {
    }

    /**
     * Writes the hint for a closed segment.
     *
     * <p>Built in a temporary file and moved into place, so a crash midway
     * through cannot leave a half-written hint under the name that startup
     * trusts. The move is the moment the hint begins to exist.
     *
     * <p><strong>Deliberately not fsynced.</strong> A hint whose contents did not
     * survive a power failure fails the checks in {@link #read} and is thrown
     * away — the same outcome as a hint that was never written, and the same
     * cost: that one segment's log is read instead. An fsync here would defend
     * against a failure the validation already handles, and it is not free. It
     * queues in the filesystem journal behind every other thread's writes, which
     * was measured making ordinary puts five times slower.
     *
     * <p>The caller must have fsynced the segment first. A hint that reaches the
     * disk before the records it describes would, after a power failure, point
     * at records that are not there.
     *
     * @param segmentBytes length of the segment as it was closed, which is what
     *                     ties this hint to that exact file
     */
    public static void write(Path dir, int fileId, List<Entry> entries, long segmentBytes)
            throws IOException {

        ByteBuffer buf = ByteBuffer.allocate(sizeOf(entries)).order(ByteOrder.BIG_ENDIAN);

        buf.putInt(MAGIC);
        buf.putShort(VERSION);
        buf.putInt(fileId);

        for (Entry entry : entries) {
            buf.putLong(entry.seq());
            buf.put(entry.type().code());
            buf.putLong(entry.recordPos());
            buf.putInt(entry.recordSize());
            buf.putShort((short) entry.key().length);
            buf.put(entry.key());
        }

        buf.putInt(entries.size());
        buf.putLong(segmentBytes);
        buf.putInt(checksumOf(buf, buf.position()));

        buf.flip();

        Path target = SegmentFiles.hintPathOf(dir, fileId);
        Path temp = SegmentFiles.hintTempPathOf(dir, fileId);

        try (FileChannel channel = FileChannel.open(temp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {

            while (buf.hasRemaining()) {
                channel.write(buf);
            }
        }

        move(temp, target);
    }

    /**
     * Loads the hint for a segment, if there is one worth believing.
     *
     * @param segmentBytes current length of the segment, which the hint has to
     *                     agree with
     * @return the entries in the order they appear in the segment, or empty if
     *         there is no usable hint — which is not an error, only a slower
     *         startup for that segment
     */
    public static Optional<List<Entry>> read(Path dir, int fileId, long segmentBytes)
            throws IOException {

        Path path = SegmentFiles.hintPathOf(dir, fileId);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }

        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < HEADER_SIZE + FOOTER_SIZE) {
            return Optional.empty();
        }

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);

        int storedCrc = buf.getInt(bytes.length - Integer.BYTES);
        if (storedCrc != checksumOf(buf, bytes.length - Integer.BYTES)) {
            return Optional.empty();
        }

        if (buf.getInt() != MAGIC || buf.getShort() != VERSION || buf.getInt() != fileId) {
            return Optional.empty();
        }

        int footerAt = bytes.length - FOOTER_SIZE;
        int entryCount = buf.getInt(footerAt);
        if (buf.getLong(footerAt + Integer.BYTES) != segmentBytes) {
            // Written for some other state of this segment, or for a file that
            // has since been rotated, merged or truncated.
            return Optional.empty();
        }

        List<Entry> entries = new ArrayList<>(Math.min(entryCount, 1024));
        for (int i = 0; i < entryCount; i++) {
            if (buf.position() + ENTRY_FIXED_SIZE > footerAt) {
                return Optional.empty();
            }

            long seq = buf.getLong();
            byte typeCode = buf.get();
            long recordPos = buf.getLong();
            int recordSize = buf.getInt();
            int keyLen = Short.toUnsignedInt(buf.getShort());

            if (buf.position() + keyLen > footerAt) {
                return Optional.empty();
            }

            byte[] key = new byte[keyLen];
            buf.get(key);

            RecordType type;
            try {
                type = RecordType.fromCode(typeCode);
            } catch (IOException unknownType) {
                return Optional.empty();
            }

            entries.add(new Entry(seq, type, recordPos, recordSize, key));
        }

        // Trailing bytes between the last entry and the footer mean the file is
        // not what its own count says it is, checksum or no checksum.
        if (buf.position() != footerAt) {
            return Optional.empty();
        }

        return Optional.of(entries);
    }

    /** Removes a segment's hint, if it has one. Used when the segment is merged away. */
    public static void delete(Path dir, int fileId) throws IOException {
        Files.deleteIfExists(SegmentFiles.hintPathOf(dir, fileId));
        Files.deleteIfExists(SegmentFiles.hintTempPathOf(dir, fileId));
    }

    private static int sizeOf(List<Entry> entries) {
        int size = HEADER_SIZE + FOOTER_SIZE;
        for (Entry entry : entries) {
            size += ENTRY_FIXED_SIZE + entry.key().length;
        }
        return size;
    }

    /** CRC32C over bytes {@code [0, end)}. */
    private static int checksumOf(ByteBuffer buf, int end) {
        ByteBuffer view = buf.duplicate();
        view.position(0);
        view.limit(end);

        CRC32C crc = new CRC32C();
        crc.update(view);
        return (int) crc.getValue();
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some filesystems cannot promise it. A hint that appears half-formed
            // here is still refused at load, so this stays safe — only slower to
            // notice.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
