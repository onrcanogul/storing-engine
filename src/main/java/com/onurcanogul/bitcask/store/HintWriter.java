package com.onurcanogul.bitcask.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Writes hint files away from the write lock.
 *
 * <p>Rotation used to build the hint, write it, fsync it and move it into place
 * before the write that triggered the rotation could return — and with every
 * other writer queued behind it, since writes are serialised. Measured at about
 * four milliseconds, roughly half of what a rotation cost.
 *
 * <p>None of that work has a claim to be there. A hint file is a cache: if it is
 * never written, the next startup reads that segment's log, which is what every
 * startup did before hints existed. So the rotation hands the entries over and
 * carries on, and one background thread does the writing.
 *
 * <p>The queue is deliberately short. If it fills, the hint is dropped rather
 * than made to wait — the whole point is that no writer waits on a cache.
 *
 * <p>Internal to the engine.
 */
public final class HintWriter implements AutoCloseable {

    /**
     * How many hints may be waiting at once.
     *
     * <p>Deep enough that a burst of rotations does not start losing hints, and
     * still bounded, because every waiting job holds its entry list in memory.
     *
     * <p>What keeps that bound cheap is that queue depth and job size cannot both
     * be large. A job is only big when the segment it describes is big, and a big
     * segment takes a long time to fill — long enough for the writer to have
     * finished several times over. Deep queues only happen with small segments,
     * whose jobs are small.
     */
    private static final int QUEUE_CAPACITY = 64;

    /** How long {@link #close()} waits for the backlog to be written. */
    private static final long DRAIN_TIMEOUT_MILLIS = 10_000;

    private record Job(int fileId, List<HintFile.Entry> entries, long segmentBytes) {
    }

    /** Placed at the end of the queue by {@link #close()}, so the backlog is written first. */
    private static final Job STOP = new Job(-1, List.of(), -1);

    private final Path directory;
    private final BlockingQueue<Job> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY + 1);
    private final Thread worker;

    private volatile boolean closed;

    public HintWriter(Path directory) {
        this.directory = directory;
        this.worker = Thread.ofPlatform()
                .name("bitcask-hint-writer")
                .daemon(true)
                .start(this::writeUntilStopped);
    }

    /**
     * Hands over the hint for a segment that has just closed.
     *
     * <p>Called with the write lock held, so it does no I/O and never blocks. The
     * caller must have fsynced the segment already: the hint describes records
     * that have to be on the disk before anything claims they are.
     *
     * <p>The entry list is handed over, not shared — the caller must not go on
     * using it.
     */
    public void submit(int fileId, List<HintFile.Entry> entries, long segmentBytes) {
        if (closed) {
            return;
        }
        // offer, not put: a full queue means the hint is skipped, and skipping a
        // cache is always better than making a writer wait for one.
        queue.offer(new Job(fileId, entries, segmentBytes));
    }

    /**
     * Stops the writer once everything already handed over has been written.
     *
     * <p>A clean shutdown keeps its hints. Only a crash loses them, and losing
     * them costs a slow startup rather than anything else.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            // Goes in behind whatever is already queued, so the backlog is written
            // before the thread stops.
            queue.put(STOP);
            worker.join(DRAIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeUntilStopped() {
        while (true) {
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (job == STOP) {
                return;
            }
            write(job);
        }
    }

    private void write(Job job) {
        try {
            HintFile.write(directory, job.fileId(), job.entries(), job.segmentBytes());

            // The segment may have been merged away while this hint sat in the
            // queue. Checking after the file is in place rather than before is
            // what makes the two orders safe: a delete that lands first is seen
            // here, and one that lands later removes the hint itself.
            if (!Files.isRegularFile(SegmentFiles.pathOf(directory, job.fileId()))) {
                HintFile.delete(directory, job.fileId());
            }
        } catch (IOException doNotLetACacheKillTheWriter) {
            // Startup will read that segment's log. Nothing else is affected.
        }
    }
}
