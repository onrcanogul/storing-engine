# The rotation fsync: measured, and left alone

**2026-08-25 — Phase 4, a change that was not made.**

The most useful outcome a measurement can have is talking you out of the work.
This one did. No engine code changed; two experiments were enough to answer the
question.

## The question

After hint writing was moved off the write lock, one expensive thing was left in
`rotate()`: the fsync of the segment being closed. Every measurement in this phase
pointed at it. A writer's p99 was about 5 ms, which is one rotation fsync; it was
what starved compaction; and it is the only fsync paid at all under the default
`SyncPolicy.NEVER`.

Unlike the hint's fsync, this one cannot simply be deleted. It holds up the
sentence recovery depends on:

> A closed segment has reached the disk. Damage there cannot be a torn write, so
> it is real corruption.

So the question was whether restructuring — an fsync moved off the write path,
with recovery relaxed to match — would buy enough to be worth trading that
sentence for.

## Experiment 1: the ceiling

Delete the fsync entirely and measure. The code is wrong and some tests fail;
that is not the point. The number answers *how much is there to win at most.*

`RotationBench`, 100,000 puts of 512-byte values, two runs each:

| segment | | puts/sec | mean plain | mean rotating | p99 | p99.9 |
|---|---|---|---|---|---|---|
| 64 KB | as it is | 24,213 / 25,541 | 8.1 / 6.8 | 3,935 / 3,828 | 234 / 240 | 4,789 / 4,647 |
| 64 KB | **no fsync** | **323,688 / 345,817** | 2.2 / 2.1 | **72 / 71** | 11 / 10 | 85 / 83 |
| 512 KB | as it is | 164,644 / 165,790 | 2.5 | 3,404 / 3,415 | 6 / 5 | 2,315 |
| 512 KB | **no fsync** | **458,405 / 452,895** | 1.9 / 2.0 | 154 / 81 | 4 | 68 / 69 |
| 4 MB | as it is | 397,865 / 410,132 | 1.8 | 5,009 / 4,400 | 4 | 8 / 23 |
| 4 MB | **no fsync** | **488,951 / 508,688** | 2.0 / 1.8 | 99 / 106 | 4 | 33 / 10 |

A rotation costs 3,900 µs with the fsync and 72 µs without it. **The fsync is 98%
of what a rotation now costs** — everything else, creating the next file and
swapping the pointers, is the remaining 2%.

The ceiling is enormous: 13x throughput at 64 KB, 2.7x at 512 KB, 1.25x at 4 MB.
Worth investigating, on this evidence alone.

## Experiment 2: a deliberately wrong prototype

The ceiling assumes the guarantee is gone. Any real design keeps it and pays
something. Rather than build one, the cheapest version was patched in — the fsync
handed to a background thread, recovery untouched, correctness ignored — in two
variants: never waiting for it, and waiting for the previous one before starting
the next (which is what bounds a real design to one segment at risk).

64 KB, the case with the most to gain:

| | puts/sec | mean plain | mean rotating | p99 | p99.9 |
|---|---|---|---|---|---|
| as it is | ~24,900 | 7.5 | 3,880 | 237 | 4,720 |
| prototype, fire and forget | ~29,900 | 23.0 | 1,240 | 765 | 3,310 |
| prototype, one outstanding | ~29,800 | 20.1 | 1,600 | 777 | 3,580 |
| ceiling | ~334,000 | 2.2 | 72 | 10 | 84 |

**The prototype lands next to where it started, not next to the ceiling.** Of the
309,000 puts/sec available, it captures about 5,000 — roughly **2%**. At 512 KB it
captures 4%, at 4 MB 23%.

And it charges for that: the ordinary write goes from 7.5 µs to about 21 µs and
p99 triples, the same shape of regression seen when hint writing was moved off the
lock.

## The decision

The fsync stays where it is. Two percent of the available gain, in exchange for
three times the latency on ordinary writes, in exchange for relaxing the one
sentence that lets recovery tell a crash from corruption. Bad on all three counts.

## Why moving an fsync never works

This is the third time in one phase, and the pattern is now clear enough to state
as a rule.

An fsync is not work that a thread does. It is a wait for the storage device to
confirm durability, and that confirmation is serialised below the application —
in the filesystem's journal and in the device itself. Moving the call to another
thread moves *which* thread waits. It does not create a second device.

So there are only two things that ever help:

1. **Do not fsync at all**, when what it protects is already protected some other
   way. This is what worked for hint files: a torn hint fails its checksum and is
   discarded, so preventing the tear bought nothing the reader was not already
   doing.
2. **fsync less often.** The cost is per operation, not per byte — proved here by
   the cost being flat across a 64x range of segment sizes. Fewer, larger
   fsyncs is the only direction that pays.

Moving it is neither, which is why it kept failing.

## What this leaves

Rule 1 does not apply: this fsync protects something real.

Rule 2 does, and in two ways. Its frequency is already a setting — segment size
decides how often a rotation happens, and the numbers above are the trade laid
out (p99.9 of 4,720 µs at 64 KB against 15 µs at 4 MB). And the remaining Phase 4
item, group commit, is rule 2 applied to the other fsync in the system: the one
`SyncPolicy.ALWAYS` pays on every write.

## Caveats

- Apple M3, macOS 26.6.2, APFS on internal SSD, OpenJDK 21.0.5.
- "As it is" was re-measured in the same session as the experiments. It reads
  lower than the numbers in the earlier note (24.9k against ~29k) — machine state
  drifts between sessions, which is why comparisons are only made within a
  session.
- The prototype is not a fair implementation of a real design; it is a best case
  for one. A correct version would be slower, not faster.
