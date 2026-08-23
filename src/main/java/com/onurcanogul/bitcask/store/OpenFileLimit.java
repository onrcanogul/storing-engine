package com.onurcanogul.bitcask.store;

import java.io.IOException;
import java.util.Locale;

/**
 * Turns descriptor exhaustion into an error that says what happened.
 *
 * <p>Every segment is held open, because the index points into all of them. So
 * the number of open descriptors grows with the data, and a large enough store
 * will hit the per-process limit — 1024 by default on most Linux systems.
 *
 * <p>The operating system reports this as a bare {@code Too many open files},
 * which gives no hint that segments are the cause or that the limit is
 * adjustable. This class supplies both.
 *
 * <p>No channel cache yet: with 128 MB segments the limit is reached at roughly
 * 128 GB of data, and this engine's real ceiling arrives long before that — that
 * much data would need tens of gigabytes of index memory. Whether a cache is
 * worth its cost is a question for the measurement phase, not a guess now.
 *
 * <p>Internal to the engine.
 */
public final class OpenFileLimit {

    private static final String SYMPTOM = "too many open files";

    private OpenFileLimit() {
    }

    /**
     * @return an explanatory exception if {@code cause} is descriptor
     *         exhaustion, otherwise {@code cause} untouched
     */
    public static IOException describe(IOException cause, int openSegments) {
        String message = cause.getMessage();
        if (message == null || !message.toLowerCase(Locale.ROOT).contains(SYMPTOM)) {
            return cause;
        }

        return new IOException(
                "ran out of file descriptors with " + openSegments + " segment(s) open. "
                        + "Every segment is held open because the index points into all of them, "
                        + "so a store needs at least as many descriptors as it has segments. "
                        + "Raise the limit (ulimit -n) or use a larger maxSegmentSize.",
                cause);
    }
}
