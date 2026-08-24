# What a rotation costs the write that triggers it

**2026-08-24 — Phase 4, baseline.** Taken before any change, so that the work
that follows can be judged against it rather than against an intuition.

## The question

Writes are serialised on one lock. A `put` that fills the active segment also
performs the rotation, and does it with every other writer queued behind it. How
much does that one write pay, and how much of it has to be there?

## How it was measured

`src/test/java/com/onurcanogul/bitcask/bench/RotationBench.java`, run by hand:

```bash
mvn -q test-compile
java -cp target/classes:target/test-classes com.onurcanogul.bitcask.bench.RotationBench
```

100,000 puts of 512-byte values, `SyncPolicy.NEVER`, a single writer, after a
20,000-put warmup that is thrown away. Each put is timed on its own, and the
writes that rotated are averaged separately from the writes that did not —
an average over both hides the entire effect.

Segment size is swept rather than fixed, because it is the only thing deciding
how often a rotation happens.

Machine: Apple M3, macOS 26.6.2, APFS on internal SSD, OpenJDK 21.0.5. All times
in microseconds.

## Baseline — three runs

| segment | rotations | puts/sec | mean plain | mean rotating | p50 | p99 | p99.9 | max |
|---|---|---|---|---|---|---|---|---|
| 64 KB | 840 | 13,687 | 6.0 | 7,972 | 3 | 249 | 9,186 | 30,216 |
| 64 KB | 840 | 13,577 | 6.2 | 8,010 | 4 | 114 | 9,268 | 46,936 |
| 64 KB | 840 | 14,840 | 5.0 | 7,418 | 3 | 158 | 8,777 | 52,549 |
| 512 KB | 104 | 97,582 | 3.0 | 6,935 | 2 | 7 | 5,245 | 10,348 |
| 512 KB | 104 | 109,806 | 2.3 | 6,474 | 1 | 6 | 4,149 | 13,465 |
| 512 KB | 104 | 89,971 | 3.3 | 7,483 | 2 | 9 | 5,424 | 11,080 |
| 4 MB | 13 | 314,196 | 2.0 | 8,882 | 1 | 5 | 10 | 9,609 |
| 4 MB | 13 | 342,853 | 1.9 | 7,480 | 1 | 4 | 17 | 8,922 |
| 4 MB | 13 | 304,160 | 2.1 | 8,794 | 1 | 5 | 29 | 10,892 |

Stable across runs, and the shape is the same at every segment size.

## What it says

**A rotating write costs about 8 milliseconds. A plain one costs about 2
microseconds.** Three orders of magnitude, on the same code path, decided by
nothing but whether that write happened to be the one that filled the segment.

**The cost of a rotation does not depend on segment size.** It is roughly 7–9 ms
whether the segment being closed holds 64 KB or 4 MB, because what is being paid
for is fsync latency and file-system metadata work, not bytes. Segment size
decides only *how often* it is paid.

**Which is why throughput moves 23x across the sweep** — 13,687 puts/sec at
64 KB against 314,196 at 4 MB — with the engine doing exactly the same work per
record. The whole difference is rotation frequency.

**The tail is where it surfaces.** At 64 KB, one write in 119 rotates, so it
lands inside p99: 249 µs at p99 against 3 µs at p50. At 4 MB, one in 7,700
rotates, and it falls out of p99.9 entirely — but the worst case is unchanged at
~9 ms, because a rotation costs what it costs.

Correcting an earlier estimate made from the Phase 2 notes: a rotation was
assumed to be dominated by one fsync at 500–1000 µs. It is not. It is around
eight times that, because Phase 3 added a second fsync to the same critical
section.

## Where the eight milliseconds go

The same benchmark with the hint write commented out of `rotate()`, to size its
share:

| segment | puts/sec | mean rotating | p99 | p99.9 |
|---|---|---|---|---|
| 64 KB | 29,811 | 3,438 | 291 | 4,109 |
| 512 KB | 165,277 | 3,508 | 5 | 2,324 |
| 4 MB | 370,565 | 4,709 | 5 | 30 |

**Writing the hint file is about half of a rotation** — roughly 4 ms of the 8.
It is not one fsync but a whole sequence: build the buffer, create a temporary
file, write it, fsync it, atomically move it into place. Four file-system
operations, all inside the write lock.

Removing it from the critical path **doubles throughput** in the rotation-heavy
configuration, 13.7k to 29.8k puts/sec, and takes p99.9 from 9.2 ms to 4.1 ms.

The remaining ~3.5–4.7 ms is the segment's own fsync plus creating the next
segment file.

## What this justifies

Nothing about the hint file needs to be written while the lock is held. It is a
cache: if it never reaches the disk, the next startup reads that segment's log,
which is the documented fallback. The measurement says that half a rotation is
being spent on work with no claim to be there.

The segment fsync is a different matter — the guarantee that a closed segment is
on the disk is what lets recovery treat damage there as real corruption rather
than as a torn tail. Moving it costs something real, and is a separate decision
to be made with its own numbers.

## Caveats

- One machine, one filesystem. APFS journals metadata; the cost of the create
  and the rename would differ elsewhere.
- The page cache is warm throughout. This measures the write path, not reads.
- A single writer thread, so it shows what a rotation costs the writer doing it,
  not yet what it costs the writers queued behind it.
- `segmentCount()` is called once per put to attribute rotations. It reads a map
  size and sits outside the timed region.
