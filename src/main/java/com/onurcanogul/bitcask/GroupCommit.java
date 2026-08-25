package com.onurcanogul.bitcask;

import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * Lets many writes share one fsync.
 *
 * <p>An fsync costs the same whether one record or a thousand are waiting behind
 * it: what is being paid for is a round trip to the storage device, not the bytes
 * that travel. So a writer that has just appended a record does not need an fsync
 * of its own. It needs *an* fsync to happen that covers its record, and any
 * fsync issued after that record was written does.
 *
 * <p>The first writer to arrive becomes the leader: it performs the fsync while
 * the others wait. Everything appended before that fsync began is durable when it
 * returns, so all of them are released together. A writer arriving mid-fsync
 * cannot be covered by it — its record may not have existed when the fsync
 * started — so it waits and leads the next round.
 *
 * <p>The promise does not change. {@code put} still returns only once its own
 * record is on the disk. It is the same promise, bought in bulk.
 *
 * <p>Internal to the engine.
 */
final class GroupCommit {

    /**
     * Performs one fsync.
     *
     * @return the highest sequence number that is on the disk once it returns
     */
    @FunctionalInterface
    interface Sync {
        long syncNow() throws IOException;
    }

    private final Sync sync;

    /** Every record with a sequence number at or below this is on the disk. */
    private long durableSeq;

    /** Whether a leader is fsyncing right now. */
    private boolean syncing;

    GroupCommit(Sync sync) {
        this.sync = sync;
    }

    /** Returns once the record with this sequence number is on the disk. */
    void awaitDurable(long seq) throws IOException {
        while (true) {
            synchronized (this) {
                // Someone else is already on the way to the disk. Their fsync may
                // or may not cover this record; that is settled on waking.
                while (durableSeq < seq && syncing) {
                    waitForLeader();
                }
                if (durableSeq >= seq) {
                    return;
                }
                syncing = true;
            }

            // Outside the lock on purpose: writers must be able to keep appending
            // during the fsync. Those records are what make the next batch worth
            // batching.
            IOException failure = null;
            long reached = 0;
            try {
                reached = sync.syncNow();
            } catch (IOException e) {
                failure = e;
            }

            synchronized (this) {
                syncing = false;
                if (failure == null && reached > durableSeq) {
                    durableSeq = reached;
                }
                notifyAll();
            }

            if (failure != null) {
                throw failure;
            }
            // Round again: leading once always covers this record, but saying so
            // here would be relying on that rather than checking it.
        }
    }

    /** Publishes durability someone else established, so nobody fsyncs again for it. */
    synchronized void published(long seq) {
        if (seq > durableSeq) {
            durableSeq = seq;
            notifyAll();
        }
    }

    private void waitForLeader() throws InterruptedIOException {
        try {
            wait();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for a write to reach the disk");
        }
    }
}
