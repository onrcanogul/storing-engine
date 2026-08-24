# Compaction losing the lock to the writer

**2026-08-24 — Phase 4, second change.**

## The question

A merge takes the write lock to decide about a record and to copy it, and it did
so once per record. A writer holds the same lock for each write. `synchronized`
promises no fairness and the thread that just released a lock is the one still
running, so it usually takes it straight back — which suggested the merge could
be starved by a writer that never pauses.

This is not only a latency question. An engine whose compaction cannot finish
does not reclaim its garbage, so the store grows without bound while every
operation still reports success. Slow compaction and no compaction are the same
outcome given enough time.

## What was found first

`MergeLivenessTest` merges 8,000 records — 2,000 live, interleaved with filler
that has since become garbage — while one thread does nothing but write to a
separate key range.

Before the change, that merge did not finish inside 30 seconds. An earlier
variant, one where every record turned out to be stale so nothing was copied at
all, took 16.5 seconds for 4,000 records: about **4 ms per record**, for a lock
held some five microseconds at a time.

Four milliseconds is not a coincidence. It is what one rotation fsync costs, and
rotation happens with the write lock held. **The merge was getting a turn roughly
once per rotation the writer performed** — it advanced only while the writer was
blocked in the kernel.

## The change

The merge now takes the lock once per batch of records rather than once per
record. Nothing about the guarantee moves: the liveness check and the copy still
happen under one lock, for a whole batch instead of one record.

Batching does not make the merge win the lock more often. It makes each win worth
more.

## The sweep

`MergeBench`, same dataset, batch size swept by editing the constant and
rebuilding:

| batch | merge ms | records moved | writer puts/sec | p50 | p99 | p99.9 | max |
|---|---|---|---|---|---|---|---|
| 1 | 20,244 | 2,000 | 2,746 | 11 | 5,075 | 5,825 | 100,660 |
| 8 | 4,923 | 2,000 | 2,559 | 10 | 4,866 | 5,515 | 100,275 |
| 64 | 3,621 | 2,000 | 2,430 | 9 | 4,831 | 5,579 | 83,144 |
| 512 | 3,395 | 2,000 | 2,415 | 12 | 4,714 | 5,328 | 96,048 |

**Merge time falls 5.6x**, almost all of it between a batch of 1 and 8.

**The trade this was supposed to involve did not appear.** Holding the lock for
64 records instead of one should have shown up in the writer's tail, and it did
not: p99 moved from 5,075 µs to 4,831 µs — in the wrong direction for batching to
explain, and inside the noise either way.

The reason is in the p50/p99 gap. The writer's median is 9 µs and its p99 is
around 5 ms, which is one rotation fsync. **A writer's tail is dominated by its
own rotation, not by waiting for a merge.** The contention that was supposed to
be visible is hiding underneath a larger cost that was already there.

Sixty-four was chosen: the knee of the curve. Past it the gain is single digits
while the lock is held far longer, and the reason the cost is currently invisible
— constant rotation — will not hold on a store with larger segments.

## What to take from it

The fix worked and the reasoning behind it was right, but the *model* was only
half right. The prediction "batching trades writer latency for merge throughput"
was wrong, and it was wrong for an interesting reason: it assumed the write lock
was the writer's main cost. It is not. The fsync inside it is.

That is the second time in this phase that a cost turned out to be somewhere
other than where the lock was — the first being hint writing, where moving work
off the lock simply moved the queue into the filesystem. Both point the same way:
**the lock is rarely the thing to look at first.**

## Caveats

- Apple M3, macOS 26.6.2, APFS, OpenJDK 21.0.5.
- 512-byte segments, so the writer rotates roughly every twelve writes. This is
  what buries the batching cost, and a store with realistic segment sizes would
  not have that cover.
- `max` swings from 83 ms to 100 ms across the sweep. It is one sample and means
  nothing here.
- The merge moves 2,000 records in every row, so the rows are comparable.
