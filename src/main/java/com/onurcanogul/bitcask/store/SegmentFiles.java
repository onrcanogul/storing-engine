package com.onurcanogul.bitcask.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Naming and discovery of segment files.
 *
 * <p>Segments are named {@code data-0000000001.log}. The number is the file id:
 * it never repeats and never goes backwards, so the highest-numbered file is
 * always the active one.
 *
 * <p>Ten digits because the file id is an {@code int}, whose ceiling is
 * 2,147,483,647 — exactly ten digits. The format cannot describe an id the type
 * cannot hold, and vice versa.
 *
 * <p>Zero padding is not needed by this code, which parses the number and sorts
 * numerically. It is there for everything that sorts as text: {@code ls}, shell
 * completion, file managers, and whoever ends up reading a directory listing
 * while debugging.
 *
 * <p>There is deliberately no metadata file recording which segment is active.
 * Such a file could disagree with reality if a crash landed between updating it
 * and creating the segment, and there would be no way to tell which one lied.
 * The directory listing is the single source of truth.
 *
 * <p>Internal to the engine.
 */
public final class SegmentFiles {

    /** The single-file name used before segment rotation existed. */
    public static final String LEGACY_LOG_NAME = "data.log";

    public static final int FIRST_FILE_ID = 1;

    private static final String PREFIX = "data-";
    private static final String SUFFIX = ".log";
    private static final String HINT_PREFIX = "hint-";
    private static final String HINT_SUFFIX = ".idx";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final int DIGITS = 10;

    private static final Pattern SEGMENT_NAME =
            Pattern.compile("^" + PREFIX + "(\\d{" + DIGITS + "})" + Pattern.quote(SUFFIX) + "$");

    private SegmentFiles() {
    }

    public static String fileName(int fileId) {
        return PREFIX + String.format("%0" + DIGITS + "d", fileId) + SUFFIX;
    }

    public static Path pathOf(Path dir, int fileId) {
        return dir.resolve(fileName(fileId));
    }

    /**
     * Where a segment's hint file lives: beside it, same number, different name.
     *
     * <p>Not matched by {@link #SEGMENT_NAME}, so hint files are invisible to
     * segment discovery. A directory listing stays the source of truth about
     * which segments exist, and hints cannot vote.
     */
    public static Path hintPathOf(Path dir, int fileId) {
        return dir.resolve(HINT_PREFIX + String.format("%0" + DIGITS + "d", fileId) + HINT_SUFFIX);
    }

    /** Where a hint is built before it is moved into place. */
    public static Path hintTempPathOf(Path dir, int fileId) {
        return dir.resolve(hintPathOf(dir, fileId).getFileName() + TEMP_SUFFIX);
    }

    /** @return the file id, or empty if the name is not a segment name */
    public static OptionalInt parseFileId(String fileName) {
        Matcher matcher = SEGMENT_NAME.matcher(fileName);
        if (!matcher.matches()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException tooLargeForAnInt) {
            // Ten digits can spell a number above Integer.MAX_VALUE. Such a file
            // cannot have been written by this engine.
            return OptionalInt.empty();
        }
    }

    /** @return every segment id in the directory, in numeric order */
    public static List<Integer> listFileIds(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Integer> fileIds = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.map(path -> path.getFileName().toString())
                    .map(SegmentFiles::parseFileId)
                    .filter(OptionalInt::isPresent)
                    .forEach(id -> fileIds.add(id.getAsInt()));
        }
        fileIds.sort(Comparator.naturalOrder());
        return fileIds;
    }

    /** @return the highest segment id, or empty if the directory holds none */
    public static OptionalInt activeFileId(Path dir) throws IOException {
        List<Integer> fileIds = listFileIds(dir);
        return fileIds.isEmpty()
                ? OptionalInt.empty()
                : OptionalInt.of(fileIds.get(fileIds.size() - 1));
    }

    /**
     * Renames a pre-rotation {@code data.log} to {@code data-0000000001.log}.
     *
     * <p>A rename rather than a refusal, because a change to the engine must not
     * invalidate data that was written successfully. Ignoring the old file would
     * be worse than failing: the store would open, look empty, and give no hint
     * that the data is still sitting on disk.
     *
     * @throws IOException if both a legacy log and real segments exist, since
     *                     there is no way to know where the legacy file belongs
     *                     in the order
     */
    public static void adoptLegacyLog(Path dir) throws IOException {
        Path legacy = dir.resolve(LEGACY_LOG_NAME);
        if (!Files.exists(legacy)) {
            return;
        }

        List<Integer> existing = listFileIds(dir);
        if (!existing.isEmpty()) {
            throw new IOException("found both " + LEGACY_LOG_NAME + " and " + existing.size()
                    + " segment file(s) in " + dir
                    + "; cannot tell where the legacy log belongs. Move it aside by hand.");
        }

        Files.move(legacy, pathOf(dir, FIRST_FILE_ID));
    }
}
