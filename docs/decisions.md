# Decisions

Why the engine is the way it is, in the order the questions came up. The
[design spec](superpowers/specs/2026-08-22-bitcask-core-design.md) covers Phases
1 and 2, which were designed on paper before any code. This picks up at
compaction, where the decisions started coming from running code and from
measurements rather than from reasoning alone.

Each entry says what was chosen, what was turned down, and what it cost —
including the times the reasoning was wrong. Those are the entries worth
rereading.

---

## Phase 3 — Compaction

### A merge copies a record only if the index points at exactly that record

Not "is this key still live" but "is this record still the current one": same
segment, same offset. A key can be live while the record in hand is a corpse,
because the key was rewritten into a newer segment.

The check and the copy happen under one lock. A `put` landing between them would
make the record stale, and copying it anyway would bring an old value back.

**Turned down:** comparing sequence numbers instead of position. Position also
rules out another copy of the same key, and it is the question actually being
asked.

### A tombstone is dropped once nothing older survives

A deletion cancels a PUT older than itself. If every segment older than the
tombstone's is being deleted in this same merge, there is nothing left for it to
cancel.

Without this rule a deleted key is paid for on every merge, forever — the
tombstone is copied forward indefinitely to guard a PUT that no longer exists.

**Cost:** it depends on segments being deleted oldest-first. Delete the
tombstone's segment before the PUT's and the key comes back. The ordering is not
an optimisation; it is load-bearing.

### The copies reach the disk before any source is deleted

One fsync per merge, before the delete loop. Skip it and a power failure between
the two takes the copy and the original together, which turns compaction into
data loss.

**How it was found:** not by a crash test — those cannot see it. `kill -9` takes
the process, not the page cache, so an engine that never fsyncs passes every one
of them. It was found by counting fsyncs and asserting on their order.

### The engine carries a seam for crash tests

Named points inside a merge, a no-op unless a test sets it, so a forked JVM can
halt exactly between a copy and a delete.

**Turned down:** killing at a random moment and repeating. The window that
matters is microseconds wide; a random kill would essentially never land in it.

**Cost:** production code that exists only for tests. Accepted, because the
alternative is not testing the thing at all.

### A hint file is a cache, never a source of truth

Startup read every byte of every segment — values included — to end up storing a
key, an offset, a length and a sequence number. Each closed segment now carries a
summary of exactly that and nothing else, and a segment with one is loaded
without its log being opened.

Anything doubtful about a hint means it is dropped and the log is read: bad
checksum, a length that disagrees with the segment, entries that do not run end
to end. Refusing a good hint costs one slow segment scan. Believing a bad one
loses data with nothing to notice.

**Turned down:** one central index file on disk. A single corruption would then
cost the whole startup, where per-segment hints degrade one segment at a time.

**Turned down:** keeping only the records still live at rotation. Smaller files,
but the garbage counters are rebuilt by watching records supersede each other
during replay, so omitting the superseded ones would mean carrying the dead-byte
totals in the file instead — and those keep changing after the segment closes.

### Hint entries are collected as the writer writes, and seeded from recovery

The writer already knows the key, offset, length and sequence number of every
record it appends. Keeping them costs nothing and spares the segment from being
read back at rotation.

The trap: after a restart the writer knows nothing about what the active segment
already holds, so a hint written from that memory would describe half a segment
while claiming the whole of it. Recovery hands back what it found in the active
segment and the writer carries on from there.

**What this taught:** two defences were in play and the test was only holding
one. Removing the seeding changed nothing, because loading refuses any hint whose
entries do not span the whole segment — the data stayed correct, the *hint*
quietly stopped existing. The test now asserts that every closed segment was
loaded from a hint, not merely that the data survived.

### Concurrency tests are bounded by time, never by a count of work

The first version of them hung forever. A merge takes the write lock once per
record and `synchronized` promises no fairness, so a writer that never pauses
starves it. "Twenty merges" is not a bound: it hangs instead of failing.

**Rule:** anything running under contention gets a deadline and a `@Timeout`.

---

## Phase 4 — Durability and contention

### Nothing is changed before it is measured, and the numbers are kept

Every change in this phase has a note in [measurements](measurements/) recording
what was predicted, what happened, and where the two differed. The prediction
being wrong is the part worth keeping.

### Hint files are written off the write lock

Rotation used to build the hint, write it, fsync it and move it into place while
holding the write lock, with every other writer queued behind it. Measured at
about four milliseconds — half of what a rotation cost.

A short queue bounds the memory. If it fills, the hint is dropped: nothing should
wait on a cache. `close()` drains the backlog, so a clean shutdown keeps its
hints.

**What this taught, and it is the best lesson of the phase:** moving the work off
the lock made things *worse*. The ordinary put went from 6 µs to 28 µs and p99
eleven times worse. fsync does not queue on the application's lock — it queues in
the filesystem journal, where the background thread and the writer met again.
**A lock is not the only queue in the system.**

### Hint files are not fsynced

The fsync was defending against a failure the reader already handles. A hint
whose contents did not survive a power cut fails its checksum and is thrown away,
which is the same outcome as a hint that was never written and costs the same:
one segment's log is read.

Removing it doubled throughput against the baseline and put the ordinary write
back where it started.

**What this taught:** validation can stand in for durability. If damage can be
*recognised*, it does not always have to be *prevented* — and recognising it is
often far cheaper. The tests that made this safe to do were already there, written
in Phase 3 for a different reason.

### A merge takes the lock once per batch of records

Once per record loses to a writer that never pauses: measured at about four
milliseconds per record, which is one rotation fsync. The merge was advancing
only while the writer was blocked in the kernel.

Batching does not make the merge win the lock more often — it makes each win
worth more. Sixty-four, chosen at the knee of a sweep: 20.2 s at a batch of 1,
4.9 s at 8, 3.6 s at 64, 3.4 s at 512.

**What this taught:** the expected trade did not exist. Holding the lock 64 times
longer was supposed to cost the writer latency, and the writer's tail did not
move — because a writer's tail is its own rotation fsync, not waiting for a
merge. The model was wrong in an informative way: it assumed the lock was the
writer's main cost.

**Why not 512:** the cost is currently invisible because the writer rotates
constantly at the segment size used in the benchmark. On a store with realistic
segments that cover disappears, and 512 would then be holding the lock for a long
time to buy 6%.

---

## What the phases taught, in general

**`kill -9` is not power loss.** A process crash cannot test an fsync: the page
cache belongs to the kernel and outlives the process. Anything that depends on
the *order* writes reach the disk needs a different kind of test — counting the
calls, not killing the process.

**The lock is rarely the thing to look at first.** Twice in one phase, a cost
that looked like lock contention was somewhere else: once in the filesystem
journal, once in an fsync the lock happened to be wrapped around.

**A test written after the code proves nothing until it has been seen red.**
Where that is impossible because the guarantee already holds, mutate the engine
and check the test notices. Two tests here were rewritten after surviving a
mutation they should have caught.

**Time is the only honest bound for contended work.** A count of iterations
assumes progress. Under contention that assumption is exactly what is in
question.

**Invariants are the real currency.** Every durability question in this project
came down to a sentence someone has to be able to rely on — *a closed segment is
on the disk*, *the copies exist before the sources are deleted*, *a hint is never
believed without proof*. Optimisations are cheap when they do not touch one of
those sentences and expensive when they do, and knowing which is which is most of
the work.
