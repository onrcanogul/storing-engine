# Group commit: many writes, one trip to the disk

**2026-08-25 — Phase 4, third change.**

## The question

`SyncPolicy.ALWAYS` fsynced on every write, inside the write lock. An fsync costs
the same whether one record or a hundred are waiting behind it — it is a round
trip to the device, not a transfer of bytes — so in principle one of them could
carry every writer waiting at that moment.

It could not, because the fsync happened while the lock was held. No second write
could even be appended during it. Every writer bought its own.

## Before

`SyncBench`: N threads writing 512-byte values for three seconds each,
`SyncPolicy.ALWAYS`, segments large enough that rotation is not what is being
measured.

| threads | puts/sec | fsync/sec | puts per fsync | p50 | p99 | p99.9 |
|---|---|---|---|---|---|---|
| 1 | 353 | 353 | 1.00 | 2,986 | 3,571 | 4,986 |
| 2 | 354 | 354 | 1.00 | 3,010 | 38,999 | 81,701 |
| 4 | 352 | 352 | 1.00 | 5,954 | 69,036 | 99,009 |
| 8 | 365 | 365 | 1.00 | 3,004 | 139,974 | 210,943 |

**Puts per fsync is exactly 1.00 at every thread count** — the number that says
nobody is sharing anything.

Throughput is flat at about 353/sec no matter how many threads write, which is
one fsync at roughly 2.8 ms repeated forever. Adding threads buys nothing and
costs a great deal: p99.9 goes from 5 ms to 211 ms, because a writer waits behind
every other writer's private trip to the disk, and an unfair lock means some wait
far longer than their turn.

## The change

`put` appends under the write lock, **releases it**, and only then waits for its
record to reach the disk. The first writer to wait becomes the leader and
performs the fsync; the others wait on it. Everything appended before that fsync
began is durable when it returns, so all of them are released together.

The promise is unchanged. `put` still returns only once its own record is on the
disk. It is the same promise, bought in bulk.

## After

| threads | puts/sec | fsync/sec | puts per fsync | p50 | p99 | p99.9 |
|---|---|---|---|---|---|---|
| 1 | 388 | 388 | 1.00 | 2,807 | 4,048 | 6,970 |
| 2 | 524 | 369 | 1.42 | 3,299 | 7,607 | 12,963 |
| 4 | 1,034 | 376 | 2.75 | 3,467 | 8,315 | 18,056 |
| 8 | 1,760 | 362 | 4.87 | 4,347 | 9,092 | 13,908 |

| threads | throughput | p99 | p99.9 |
|---|---|---|---|
| 1 | 353 → 388 | 3,571 → 4,048 | 4,986 → 6,970 |
| 2 | 354 → 524 (**1.5x**) | 38,999 → 7,607 (**5x better**) | 81,701 → 12,963 (**6x**) |
| 4 | 352 → 1,034 (**2.9x**) | 69,036 → 8,315 (**8x**) | 99,009 → 18,056 (**5x**) |
| 8 | 365 → 1,760 (**4.8x**) | 139,974 → 9,092 (**15x**) | 210,943 → 13,908 (**15x**) |

Throughput and the tail improved together, which is unusual enough to be worth
saying twice. Nothing was traded.

**fsyncs per second did not change** — about 370 in every row, before and after.
That is the device's rate and no amount of software moves it. What changed is how
many writes each one carries.

## Why it is 4.87 and not 8

With eight threads a batch should carry eight writes. It carries about five,
because a thread released by one fsync has to append again before it can join the
next one, and while it does that the next batch may already have closed. Steady
state settles below the thread count.

Getting closer would mean letting a writer append its next record while the
previous one is still in flight, which is a different feature — pipelining — and
a different promise to reason about.

## What this confirms

The rule from the two previous experiments in this phase was: **moving an fsync
never helps, doing fewer of them does.** This is the first change of the phase
that pulled the second lever, and it is the first prediction that held.

It also explains why the earlier attempts failed so consistently. Moving an fsync
to a background thread leaves the count unchanged, and the count is the only thing
that matters at a fixed 370 per second.

## Caveats

- Apple M3, macOS 26.6.2, APFS on internal SSD, OpenJDK 21.0.5.
- Three-second runs, so at 350 puts/sec the single-threaded rows rest on about a
  thousand samples. Enough for p50 and p99, thin for p99.9.
- Segments are 64 MB here so that rotation's fsync does not enter the count. With
  small segments the two would mix.
- This is a benchmark of writers that do nothing but write. Real callers do work
  between writes, which changes how many are waiting when a batch closes — the
  batch factor is a property of the workload, not of the engine.
