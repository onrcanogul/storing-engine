# Moving the hint write off the write lock

**2026-08-24 — Phase 4, first change.** Measured against
[the baseline](2026-08-24-rotation-under-the-write-lock.md), same harness, same
machine.

## The change

Rotation used to build the hint file, write it, fsync it and move it into place
while holding the write lock. It now hands the entries to a background thread and
returns. A short queue bounds the memory; if it fills, the hint is dropped, since
nothing should wait on a cache.

## What was predicted

The baseline diagnostic — the same benchmark with hint writing commented out —
said hint writing was about half of a rotation, and that removing it from the
critical path would take 64 KB throughput from 13.7k to 29.8k puts/sec.

## What happened

| segment | | puts/sec | mean plain | mean rotating | p99 | p99.9 |
|---|---|---|---|---|---|---|
| 64 KB | before | 13,687 | 6.0 | 7,972 | 249 | 9,186 |
| 64 KB | **after** | **15,105** | **28.5** | **4,485** | **2,809** | **6,048** |
| 512 KB | before | 97,582 | 3.0 | 6,935 | 7 | 5,245 |
| 512 KB | **after** | **104,297** | **5.9** | **3,517** | **11** | **2,995** |
| 4 MB | before | 314,196 | 2.0 | 8,882 | 5 | 10 |
| 4 MB | **after** | **329,080** | **2.4** | **4,700** | **5** | **45** |

The rotation itself did exactly what was predicted: **halved, everywhere.** That
part of the model was right.

Everything else was wrong.

Throughput at 64 KB moved 10%, not 118%. And the writes that were supposed to be
unaffected got worse: **a plain put went from 6 µs to 28 µs, and p99 from 249 µs
to 2,809 µs — eleven times worse.** The change made the common case slower while
making the rare case faster.

## Why

Moving work off a lock does not remove the work. It moves the contention.

The background thread still fsyncs, and fsync does not serialise on the
application's lock — it serialises in the filesystem. Two threads issuing fsyncs
against the same volume queue behind each other in the journal, so the foreground
writer now waits on the background one, just somewhere it cannot be seen from
Java.

At 64 KB a rotation happens roughly every 0.7 ms while a hint takes about 4 ms to
write. The writer can never catch up, so the queue stays occupied and the
background fsync is effectively continuous. That is what every plain put is now
paying for.

The lesson is worth keeping: **a lock is not the only queue in the system.**
Latency that disappears from the profiler has not necessarily disappeared.

## What that points at

If the problem is the background fsync, the question is what that fsync is for.

The answer turns out to be nothing. A hint file is validated when it is read: a
CRC over the whole file, a record count, and the length of the segment it claims
to describe. A hint whose contents did not survive a power failure fails that
check and is thrown away, exactly like a hint that was never written. **The fsync
was defending against a failure the validation already handles.**

Diagnostic — the same build with `channel.force` removed from `HintFile.write`:

| segment | puts/sec | mean plain | mean rotating | p99 | p99.9 |
|---|---|---|---|---|---|
| 64 KB | 27,807 | 5.9 | 3,569 | 307 | 4,415 |
| 512 KB | 152,397 | 2.9 | 3,525 | 9 | 2,308 |
| 4 MB | 377,500 | 2.0 | 4,666 | 5 | 26 |

Against the original baseline: **throughput doubles at 64 KB** (13.7k → 27.8k),
within reach of the 29.8k ceiling measured with hints turned off entirely. The
regression in the common case is gone — a plain put is back to 5.9 µs and p99 to
307 µs.

So the win claimed for moving the hint off the lock is real, but only in
combination. On its own it is close to worthless, and at high rotation rates it
is a net loss.

## As shipped: both changes together

The fsync was removed. Three runs:

| segment | puts/sec | mean plain | mean rotating | p50 | p99 | p99.9 |
|---|---|---|---|---|---|---|
| 64 KB | 26,938 | 6.2 | 3,675 | 3 | 301 | 4,426 |
| 64 KB | 30,539 | 4.9 | 3,307 | 2 | 271 | 4,071 |
| 64 KB | 30,231 | 4.6 | 3,379 | 1 | 191 | 4,195 |
| 512 KB | 159,741 | 2.6 | 3,509 | 1 | 5 | 2,437 |
| 512 KB | 165,240 | 2.4 | 3,529 | 1 | 8 | 2,172 |
| 512 KB | 158,749 | 2.5 | 3,590 | 1 | 13 | 2,420 |
| 4 MB | 403,157 | 1.8 | 4,706 | 1 | 4 | 9 |
| 4 MB | 407,782 | 1.9 | 4,115 | 1 | 4 | 30 |
| 4 MB | 276,069 | 3.0 | 4,862 | 1 | 14 | 140 |

Against the baseline:

| | 64 KB | 512 KB | 4 MB |
|---|---|---|---|
| throughput | 13,687 → ~29,000 (**+112%**) | 97,582 → ~161,000 (**+65%**) | 314,196 → ~360,000 (**+15%**) |
| mean rotating | 7,972 → ~3,450 (**−57%**) | 6,935 → ~3,540 (−49%) | 8,882 → ~4,560 (−49%) |
| mean plain | 6.0 → ~5.2 | 3.0 → ~2.5 | 2.0 → ~2.2 |
| p99 | 249 → ~250 | 7 → ~9 | 5 → ~7 |
| p99.9 | 9,186 → ~4,200 (**−54%**) | 5,245 → ~2,340 (−55%) | 10 → ~60 |

The common case is where it started, the tail is halved, and throughput at high
rotation rates is more than double.

What is left of a rotation — about 3.5 ms — is the segment's own fsync and the
creation of the next segment file. That fsync is the one that cannot be removed
by this argument: it is what lets recovery treat damage in a closed segment as
real corruption rather than as a torn tail. Moving it is a separate decision,
with a real guarantee on the other side of the trade.

The last 4 MB run (276k, p99 14) is the noisiest number here. Two of three runs
landed above 400k. Treat the 4 MB row as "a modest improvement", not as 30%.

## Caveats

- Same machine and filesystem as the baseline: Apple M3, macOS 26.6.2, APFS.
  Journal behaviour is a large part of what is being measured here, and it is not
  the same everywhere.
- `mean plain` at 64 KB is the number that moved most, and it is the one most
  exposed to background interference. Three runs agreed (21.1, 29.1, 28.5), so
  the effect is real, but its size is machine-specific.
- The queue drops hints when full. At 64 KB the writer is permanently behind, so
  some segments end up without one. That costs startup speed, nothing else.
