# Bitcask-Style Storage Engine — Phase 1 (Core) Design

**Date:** 2026-08-22
**Status:** Approved for implementation planning
**Scope:** Phase 1 (Core) only

---

## 1. Project Overview

An embedded, append-only key-value storage engine in Java, modeled on Bitcask.
Written to deepen systems-analysis skills: the engine will be built, measured,
and then deliberately broken to observe its failure modes.

### 1.1 Phase Roadmap

| Phase | Content |
|---|---|
| **1 — Core** | Single append-only file, in-memory hash index, `put`/`get`/`delete`, crash recovery, CRC |
| 2 | Segment rotation (active file + immutable files) |
| 3 | Compaction / merge + hint files |
| 4 | Durability policies (fsync strategies, group commit, write buffering) |
| 5 | Measurement harness, metrics, p99 latency |
| 6 | Breaking it: `kill -9`, torn writes, bit rot, disk full |

This document specifies **Phase 1 only**. Later phases get their own specs.

### 1.2 Phase 1 Success Criterion

> Kill the process with `kill -9` at any arbitrary moment, then reopen the
> engine. Every write whose `put` call returned must be present. Any partially
> written record must be discarded without corrupting the engine.

This single sentence drives the entire test suite for this phase.

---

## 2. Scope

### 2.1 In Scope

- On-disk record format
- In-memory index (KeyDir) structure
- Write path, read path, delete semantics
- Startup recovery via full log replay
- Corruption detection and policy
- Lifecycle and resource management
- Test strategy

### 2.2 Explicitly Out of Scope (Phase 1)

| Excluded | Consequence accepted |
|---|---|
| Segment rotation | The single log file grows without bound |
| Compaction | Garbage (superseded records) accumulates on disk |
| Hint files | Startup is O(total log size) |
| Write buffering | One `write()` syscall per `put` |
| Performance tuning | Nothing is optimized before it is measured |
| Network layer | Embedded library only, no server, no wire protocol |
| Range scans / iteration | Hash index provides point lookups only |
| TTL, compression, encryption | Format reserves room for future addition |
| Transactions / multi-key atomicity | Each operation is independent |

---

## 3. Contract and Limits

### 3.1 API Surface

```
open(Path dir, Config config) -> Engine
put(byte[] key, byte[] value) -> void
get(byte[] key) -> byte[] | null
delete(byte[] key) -> boolean
close() -> void
```

Keys and values are raw `byte[]`. Serialization is the caller's concern, not
the engine's.

`open` returns both the engine and a **recovery report** (§9.4); the report is
not optional and cannot be ignored silently.

### 3.1.1 Configuration

| Setting | Default | Note |
|---|---|---|
| `maxValueSize` | 16 MB | Write-side limit only (§3.3) |
| `syncPolicy` | `NEVER` | `NEVER` or `ALWAYS` in Phase 1 (§6.3) |
| `recoveryMode` | `TOLERATE_TAIL` | `TOLERATE_TAIL` or `STRICT` (§9.3) |

No other tunables exist in Phase 1. Anything not listed here is a constant.

### 3.2 Ownership and Copying

| Rule | Rationale |
|---|---|
| **Keys are copied** on `put` and `delete` | The engine must not depend on caller discipline for its own correctness. A caller mutating a reused buffer would otherwise silently orphan a KeyDir entry. |
| **Values are NOT copied** on `put` | The value is written to disk and released; the engine retains no reference. Mutating it afterwards is harmless. |
| **`get` results belong to the caller** | The returned array is freshly allocated and referenced nowhere inside the engine. |

### 3.3 Limits

| Limit | Value | Configurable |
|---|---|---|
| Key length | 1 – 65,535 bytes | No (format-imposed) |
| Empty key (`keyLen == 0`) | Rejected | No |
| Max value size (write) | 16 MB default | **Yes** |
| `HARD_MAX_VALUE_SIZE` (parse) | 64 MB | **No** |
| Empty value (`valLen == 0`) | Valid | — |

**Critical rule:** a configuration change must never invalidate a record that
was previously written successfully. Write policy (`maxValueSize`) and parsing
robustness (`HARD_MAX_VALUE_SIZE`) are separate concepts and must not share a
value. Lowering `maxValueSize` must not cause previously valid records to be
treated as corrupt during recovery.

### 3.4 Threading and Process Model

- **One writer, many readers.** Writes are serialized; reads are fully parallel.
- **One process per directory**, enforced by a lock file. Two JVMs appending to
  the same log would each track their own `writePos` and overwrite each other's
  data — silent, unrecoverable corruption.
- **All keys must fit in RAM.** This is the defining constraint of the Bitcask
  model, not a deficiency. See §5.4 for the memory budget.

### 3.5 Java Version

Java 21 (LTS). Required for `java.util.zip.CRC32C` (Java 9+).

---

## 4. On-Disk Format

### 4.1 File Header — 8 bytes, once at offset 0

| Offset | Size | Field | Value |
|---|---|---|---|
| 0 | 4 | `magic` | `0x4F 0x4E 0x52 0x43` (ASCII `"ONRC"`) |
| 4 | 2 | `version` | `1` |
| 6 | 2 | `reserved` | `0` |

`magic` lets the engine reject a file that is not ours with a clear error
instead of interpreting random bytes as records. `version` makes future format
changes possible without a migration wall.

### 4.2 Record Layout — 27-byte header + payload, big-endian

| Offset | Size | Field | Description |
|---|---|---|---|
| 0 | 4 | `crc32c` | Checksum of **everything after itself** |
| 4 | 8 | `seq` | Monotonic sequence number |
| 12 | 8 | `tstamp` | Epoch milliseconds |
| 20 | 1 | `type` | `1` = PUT, `2` = TOMBSTONE |
| 21 | 2 | `keyLen` | 1–65,535 (unsigned) |
| 23 | 4 | `valLen` | 0–limit (unsigned) |
| 27 | `keyLen` | `key` | raw bytes |
| 27+`keyLen` | `valLen` | `value` | raw bytes |

**Byte order is big-endian** — the default for `ByteBuffer` and
`DataOutputStream`, and readable left-to-right in a hex dump.

### 4.3 Field Rationale

**`crc32c` — CRC32C, not CRC32.** CRC32C (Castagnoli) is computed by a hardware
instruction on modern x86 and ARM; `java.util.zip.CRC32C` uses it. Cost is
negligible relative to disk I/O.

CRC coverage is **every byte after the CRC field itself**, including all length
fields. Covering only the value would be a serious defect: a single flipped bit
in `keyLen` would go undetected, the reader would consume the wrong number of
bytes, and **all subsequent record boundaries in the file would be lost**.
Length fields determine the reader's next action and must always be under
checksum.

CRC detects random corruption with probability ~1 − 2⁻³². It is **not
cryptographic**: it does not defend against deliberate tampering.

**`seq` — the sole ordering authority.** Wall-clock time is not monotonic: NTP
can step the clock backwards, VMs jump on resume, leap seconds occur. If a
newer record carried an older timestamp, compaction would discard the *newer*
value — silently. A logical counter answers "which came later" with certainty,
which is exactly the question the engine needs.

Resolution is a second reason: `currentTimeMillis` has 1 ms granularity while
the engine can perform far more than 1,000 writes per second, so timestamp ties
are routine and would require a counter as a tiebreaker anyway.

8 bytes at 1M writes/sec lasts ~292,000 years.

**`tstamp` — informational only.**

> **The timestamp is never compared in any code path.**

It exists for debuggability (Phase 6 forensics), and as groundwork for a
possible future TTL feature. Ordering decisions use `seq` exclusively.

Cost: 8 bytes/record ≈ +5.9% space for a 100-byte value, +0.8% at 1 KB, +0.2%
at 4 KB. CPU cost is ~26 ns/record (one `currentTimeMillis` call plus the
write), which is ~0.05% of a `put` when fsync is enabled.

**`type` — an explicit field, not an encoding trick.** Using `valLen == 0` for
deletion would conflate deletion with a legitimately empty value. Using
`valLen == -1` works but overloads a length field and collides with unsigned
reads. A dedicated byte keeps intent explicit, is readable in a hex dump, and
leaves room for future record types.

**`keyLen` — 2 bytes.** The real key-size limit is RAM, not the format: at
~168 bytes of KeyDir overhead per key (§5.4), one million 64 KB keys would need
~64 GB. The format ceiling is therefore unreachable in practice, which is
exactly what a good limit looks like.

**`keyLen == 0` is invalid**, which yields a free sanity check during recovery.

### 4.4 Unsigned Read Requirement

Java has no unsigned integer types. Lengths must be masked on read:

```
int keyLen = readShort() & 0xFFFF;
long valLen = readInt() & 0xFFFFFFFFL;
```

Omitting this is a silent bug class: it never surfaces in tests with small
keys, and fails on the first key above 32 KB.

### 4.5 Deliberate Omissions

| Omitted | Reason |
|---|---|
| Per-record sync marker | Would allow resynchronizing after a corrupt region, but costs 4 bytes/record and the Phase 1 corruption policy does not need it |
| Alignment / padding to sector boundaries | Wastes space; the guarantee is hardware-dependent and unreliable. CRC plus a correct recovery policy is the real answer |
| Variable-length integers (varint) | Would shrink the header to ~10 bytes, but complicates recovery scanning and makes hex-dump debugging harder. ~10 bytes/record is the accepted cost |
| Key-value separation (WiscKey style) | Outside the Bitcask model; heavily complicates compaction |

---

## 5. KeyDir (In-Memory Index)

### 5.1 Role

Maps every live key to the location of its most recent record. This yields the
defining Bitcask property: **a read is always exactly one disk access**,
independent of dataset size. The price is that all keys must fit in RAM.

### 5.2 Key Representation

`ByteBuffer.wrap(copyOfKey)`.

`byte[]` cannot be used directly as a map key: it inherits `equals` (reference
comparison) and `hashCode` (identity hash) from `Object`. A
`HashMap<byte[], V>` compiles, runs, and never finds anything. The failure is
insidious because a test that reuses the same array reference passes.

Note: a Java `record` does not solve this either — its generated `equals` uses
`Objects.equals` on reference-typed fields, which is still reference comparison
for arrays.

`ByteBuffer` implements content-based `equals`/`hashCode`, which is why it is
chosen here for simplicity.

**Discipline rule:**

> A `ByteBuffer` used as a KeyDir key is a read-only identity. No method that
> changes its position is ever called on it. Use `duplicate()` to read its
> contents.

`equals`/`hashCode` depend on `position` and `limit`; moving the position would
make the entry unfindable while it still occupies memory.

**`wrap` does not copy.** The key must be copied before wrapping
(`ByteBuffer.wrap(Arrays.copyOf(key, key.length))`), per §3.2.

### 5.3 Entry Contents

| Field | Type | Note |
|---|---|---|
| `fileId` | int | Always `0` in Phase 1; the field exists now for Phase 2 |
| `recordPos` | long | Offset of the **record**, not the value |
| `recordSize` | int | Total record size |
| `seq` | long | Used by Phase 3 compaction to check whether a record is still current |

**Storing the record's position rather than the value's is a deliberate
choice.** Pointing at the value would read fewer bytes but make CRC
verification impossible, since the CRC covers the whole record. The extra bytes
(~43 for a 16-byte key) come from the same 4 KB page that is already being
read, so there is no additional disk access — and in exchange **every read
verifies integrity**. An engine that returns corrupted data as if it were
correct is worse than one that crashes.

### 5.4 Memory Budget

Per key, on a 64-bit JVM with compressed oops, for a 16-byte key:

| Structure | Bytes |
|---|---|
| `ConcurrentHashMap` node | ~32 |
| `ByteBuffer` wrapper object | ~56 |
| `byte[]` itself (16 header + 16 data) | 32 |
| KeyDir entry object | ~40 |
| Hash table slot share (load factor 0.75) | ~8 |
| **Total** | **~168** |

| Key count | KeyDir RAM |
|---|---|
| 1 million | ~170 MB |
| 10 million | ~1.7 GB |
| 100 million | ~17 GB |

This is accepted for Phase 1 and will be measured in Phase 5. Because the
KeyDir is purely in-memory, changing its representation later requires no
migration.

### 5.5 Map Implementation

`ConcurrentHashMap`. Reads are lock-free and fully parallel; with a single
writer there is no write contention to speak of, and no manual lock management
to get wrong.

Caveat: compound operations (`get` then `put`) are not atomic. With one writer
this does not arise in Phase 1; if needed, `compute`/`merge` are used.

### 5.6 Deletion in the KeyDir

The entry is **removed** entirely. Retaining a tombstone entry would spend the
engine's scarcest resource (RAM) on data that no longer exists. The tombstone
record on disk remains — recovery needs it to cancel the earlier PUT.

---

## 6. Write Path

### 6.1 Steps

1. Validate (key non-empty, sizes within limits)
2. Increment `seq`
3. Serialize the record, compute CRC
4. `FileChannel.write(buffer, writePos)`
5. *(optional)* `fsync`
6. Update the KeyDir
7. Advance `writePos`, return

### 6.2 Invariant: Disk Before Memory

**Step 6 always follows step 4.** If the write fails, the KeyDir must not point
at a record that does not exist. In the reverse order, a failed write leaves the
index referencing garbage and `get` returns nonsense or throws.

### 6.3 Durability Policy

`write()` does not reach the disk. It copies into the OS page cache and
returns. `fsync()` is what forces the data to durable storage, and it is
expensive.

Two distinct failure events:

| Event | Page cache | Result |
|---|---|---|
| `kill -9` (process dies) | Survives — the OS is still running | No data loss even without fsync |
| Power loss / kernel panic | Lost | Without fsync, recent writes are gone |

The Phase 1 success criterion (§1.2) is stated in terms of `kill -9` and is
therefore satisfiable without fsync. fsync matters when the machine itself
dies.

| Mode | Approx. write rate | Loss window |
|---|---|---|
| `NEVER` (default) | ~500K+/s | Last ~30 s on power loss |
| `ALWAYS` | ~1–10K/s | None |

**Phase 1 ships both modes with `NEVER` as the default**, so Phase 5 can
measure the 50–100× gap directly. `INTERVAL` and group commit are Phase 4.

### 6.4 No Application-Level Write Buffer

Each `put` issues one `write()` syscall directly. A user-space buffer would
save syscalls (~0.5–2 µs each) but would introduce data that is written but not
yet visible in the file, forcing the read path to consult two sources. The OS
page cache already provides a buffering layer.

Buffering is deferred to Phase 4, where its effect can be measured before and
after.

### 6.5 Sequence and Position Sources

- `writePos`: an in-memory counter, initialized by recovery. Calling
  `file.length()` per `put` would be a needless syscall.
- `seq`: an in-memory counter, initialized to `maxSeq + 1` by recovery. **The
  log itself is the source of the counter** — a separate counter file would
  introduce a new way for two artifacts to disagree.

Both writes and reads use positional I/O; the file pointer is never used.

---

## 7. Read Path

### 7.1 Steps

1. Look up the key in the KeyDir; if absent, return `null` (no disk access)
2. Read `recordSize` bytes from `recordPos` via positional read
3. Verify CRC
4. Verify the key in the record matches the requested key
5. Verify `type` is PUT
6. Return the value

### 7.2 Positional Reads

`FileChannel.read(buffer, position)` neither uses nor modifies the file
pointer. This allows **unbounded parallel reads through a single
`FileChannel`** without locking. A shared file pointer would have readers
corrupting each other's position.

### 7.3 Error Behavior

| Condition | Behavior | Rationale |
|---|---|---|
| CRC mismatch | Throw | `null` means "no such key"; presenting corruption as absence hides the truth. Fail loudly. |
| Key mismatch | Throw | Catches a wrong offset in the KeyDir. CRC cannot catch this — a wrong-but-intact record has a valid CRC. Cost is near zero since the bytes are already in hand. |
| `type == TOMBSTONE` | Throw | Unreachable by design (§5.6). If it happens, it is an internal inconsistency and must not pass silently. |

---

## 8. Delete Semantics

`delete(key)`:

1. If the key is absent from the KeyDir, do nothing and return `false`
2. Write a TOMBSTONE record to disk
3. Remove the KeyDir entry
4. Return `true`

Same invariant: disk before memory.

**No tombstone is written for an absent key.** Its only effect would be adding
garbage to the log; recovery has nothing to cancel.

**Tombstones persist on disk.** They are required so that recovery can cancel
the earlier PUT record. When a tombstone may safely be discarded is a Phase 3
(compaction) question — discarding one too early resurrects a deleted key.

---

## 9. Recovery

### 9.1 Algorithm

1. Validate the file header (`magic`, `version`)
2. Scan records sequentially from offset 8
3. PUT → insert into KeyDir; TOMBSTONE → remove from KeyDir
4. Track the maximum `seq` and the last valid offset
5. On completion: `writePos` = last valid offset, `seq` = maxSeq + 1

A later record supersedes an earlier one; the physical order in the file is the
correct order.

### 9.2 Validation Chain

Ordered cheapest-first, and **nothing is allocated until all of it passes**:

1. `keyLen > 0`
2. `type` is a known value (1 or 2)
3. `27 + keyLen + valLen <= bytes remaining in file`
4. `valLen <= HARD_MAX_VALUE_SIZE`
5. Only now: allocate, read, verify CRC

Check 3 is the strongest and is independent of configuration: a record claiming
to be longer than the remainder of its own file is certainly corrupt.

This ordering closes an **allocate-before-validate** vulnerability. Trusting a
corrupt length field enough to size an allocation lets a single bad byte
trigger `OutOfMemoryError`. This is a real defect class with assigned CVEs in
production parsers.

An additional free check: `seq` values must be strictly increasing. A record
whose `seq` is not greater than its predecessor's is treated as **corrupt** and
handed to the corruption policy in §9.3, exactly like a CRC failure. It cannot
be produced by a correct writer, so the only explanations are corruption or a
file that is not what it claims to be.

### 9.3 Corruption Policy

Two distinct situations that must not be treated identically:

| Situation | Cause | Correct response |
|---|---|---|
| Partial record **at the end** of the file | A write interrupted by a crash — **normal and expected** | Truncate and continue |
| Corruption **in the middle** | Disk corruption, bit rot — **abnormal** | Passing over it silently hides data loss |

They cannot be distinguished with certainty, because the length field of the
corrupt record may itself be damaged.

| Mode | Behavior |
|---|---|
| **`TOLERATE_TAIL`** (default) | Stop at the first corrupt record. Truncate the file to that offset. **Report how many bytes were discarded.** Open successfully. |
| `STRICT` | Throw on the first corrupt record. The engine does not open; human intervention required. |

RocksDB's WAL recovery uses the same shape of modes, with
`kTolerateCorruptedTailRecords` as its default.

**Governing principle: never be silent.** If even one byte is discarded, the
application is told. Silent data loss is far worse than a loud failure.

### 9.4 Recovery Report

Recovery returns a value; it does not merely log. The report carries:

| Field | Meaning |
|---|---|
| `recordsReplayed` | Number of valid records applied to the KeyDir |
| `liveKeys` | KeyDir size after replay |
| `bytesDiscarded` | Bytes truncated from the tail; `0` on a clean open |
| `truncatedAtOffset` | Offset where truncation occurred, if any |
| `reason` | Why scanning stopped: clean EOF, CRC mismatch, invalid header field, non-increasing `seq`, or short read |

`bytesDiscarded > 0` means data was lost. The application decides what that
means for it — the engine's obligation is to state it plainly rather than
resolve it unilaterally. This is the concrete mechanism behind the "never be
silent" principle in §9.3.

---

## 10. Lifecycle and Resource Management

### 10.1 `open(dir, config)`

1. Open or create the directory
2. **Acquire a lock file** via `FileChannel.tryLock()`
3. Run recovery
4. Return a ready engine

If the lock cannot be acquired, opening is refused. Two processes appending to
the same log would each maintain their own `writePos` and overwrite each
other's records.

`FileLock` is enforced at the **process** level; a separate in-JVM guard is
needed to prevent a second `open` of the same directory within one JVM.

### 10.2 `close()`

Idempotent — a second call returns silently. Closes channels, releases the
lock.

### 10.3 Other Rules

- Any operation on a closed engine throws `IllegalStateException`
- **No JVM shutdown hook.** It does not run under `kill -9` and would create
  false confidence in durability.

---

## 11. Test Strategy

### Layer 1 — Unit

- Format round-trip: write → read → identical
- Corrupt CRC is detected
- Boundary values: 1-byte key, 65,535-byte key, empty value, oversized value
- **Different-reference key test:** `get` using an array object distinct from
  the one passed to `put`. This is the test that catches the §5.2 failure mode;
  a test reusing the same reference passes even when the engine is broken.
- Mutating the caller's key array after `put` does not affect the engine
  (verifies the copy policy)
- Unsigned length handling: a key above 32 KB round-trips correctly

### Layer 2 — Model-Based

Generate random operation sequences (`put`/`get`/`delete`), apply them to both
the engine and a reference `HashMap`, and compare after every step. This finds
states that hand-written tests do not reach.

### Layer 3 — Crash

Start a separate JVM process, write continuously, `kill -9` at a random moment,
reopen, and verify:

- Every `put` that returned is present
- A partial record was discarded without corrupting the engine
- The reported discarded-byte count is accurate

This is the direct test of the Phase 1 success criterion (§1.2).

### Layer 4 — Concurrency

Readers and a writer work continuously while merges run against them. The window
worth testing — a reader holding an index entry for a segment a merge then
deletes — is a few microseconds wide and cannot be staged from outside, so it is
hit by volume rather than by arrangement. A failure shows up as a thrown
exception or a wrong value, not as a flaky assertion.

**Such a test is bounded by wall clock, never by a count of work done.** A merge
takes the write lock once per record and `synchronized` promises no fairness, so
a writer that never pauses can starve one indefinitely. A loop of "twenty merges"
is not a bound: it hangs instead of failing. Every test in this layer also
carries a `@Timeout`, so a starved run is reported rather than waited on.

### Layer 5 — Ordering

Durability is a property of the *order* in which two writes reach the disk, and
that order cannot be observed from the outside.

**A process crash cannot test an fsync.** `kill -9` takes the process, not the
page cache; the kernel writes those pages out afterwards, so an engine that never
fsyncs survives every crash test unharmed. Only power loss tells the difference.

The engine therefore counts its own fsyncs, and the test asserts on the order of
the calls: that a merge has synced its copies before it deletes the segments they
came from. This layer exists because Layer 3 structurally cannot reach it.

### Layer 6 — Crash At A Chosen Point

Layer 3 kills a process at an arbitrary moment, which is the right test for a
writer doing the same thing over and over. It is the wrong test for compaction,
where the moments that matter are between a copy and a delete and pass in
microseconds.

The engine exposes a seam — named points inside a merge, a no-op unless a test
sets it — and a forked JVM halts at one of them. Each point becomes one named
test, and the invariant is checked on whatever the directory was left holding:
nothing lost, nothing rolled back to an older value, nothing resurrected.

The seam is the cost of the layer. It is production code that exists for tests,
which is worth it here because the alternative is a random kill that almost never
lands in the window.

### On Tests That Never Fail

A test written after the code it covers has proved nothing until it has been seen
red. Where that was not possible — the guarantee already held — the engine is
mutated instead: a merge copy made to carry its original sequence number, a
recovery step made to forget what it found. A test that stays green under the
mutation is not testing what its name claims, and two of them were rewritten for
exactly that reason.

### TDD Note

Implementation order will differ from the order of this document. The format
round-trip (§4) is the most isolated and testable starting point; the contract
(§3) produces no code of its own.

---

## 12. Deferred Work

### 12.1 To Later Phases

| Item | Phase |
|---|---|
| ~~Tombstone lifetime — when a tombstone may be discarded~~ — done in Phase 3 | 3 |
| Write buffering, measured before and after | 4 |
| `INTERVAL` fsync mode and group commit | 4 |
| Hash-based KeyDir (~50 bytes/key instead of ~168, with collisions resolved by verifying the key read from disk) | 5–6, if measurement justifies it |
| Custom key wrapper with cached hash (~24 bytes instead of ~56) | 5, if measurement justifies it |
| Better hash function (xxHash / Murmur3) if key distribution proves poor | 5 |

### 12.2 Phase 5 Measurement Backlog

Collected during design; each item exists because a decision was made on
reasoning that should be confirmed with numbers.

1. **Header overhead curve.** Vary value size from 64 B to 4 KB; plot
   bytes/second against records/second and locate the inflection point.
   Quantifies the 27-byte header's real cost.
2. **Clocksource cost.** Check the host's clocksource
   (`/sys/devices/system/clocksource/clocksource0/current_clocksource` on
   Linux). With `tsc`, `currentTimeMillis` costs ~20–30 ns; with `hpet` or
   `acpi_pm` it can cost 500 ns – 1 µs, which would exceed the cost of writing
   the record. Micro-benchmark it. If it is pathological, a coarse clock (a
   `volatile long` refreshed every ms, as nginx and Netty do) fixes it **without
   changing the format**.
3. **KeyDir memory profile.** Measure actual bytes per key against the ~168
   estimate. Decide whether the custom wrapper or hash-based KeyDir is
   warranted.
4. **fsync `NEVER` vs `ALWAYS`.** Measure the expected 50–100× throughput gap
   directly.
5. **Is a channel cache worth it?** Every segment is held open, so descriptor
   use grows with the data — 1024 segments at 128 MB is about 128 GB. An LRU
   channel cache would cap that, at the cost of locking on the read path and a
   reopen-and-retry path when a channel is evicted mid-read. Measure first
   whether the limit is ever reached before the index memory ceiling, which
   arrives much earlier: 128 GB of 1 KB values is 128 million keys, or roughly
   23 GB of index.
6. **GC behavior with large values.** G1GC treats objects larger than half a
   region (regions are 1–32 MB, chosen automatically) as *humongous
   allocations*: they bypass young-generation handling, go straight to old gen,
   fragment the heap, and can trigger concurrent cycles. With a 16 MB value
   limit this is reachable. Expect it to show up in p99 latency, and do not
   mistake it for disk latency.

---

## 13. Known Limits

Stated deliberately — knowing what an engine does *not* guarantee matters as
much as knowing what it does.

| Limit | Nature |
|---|---|
| All keys must fit in RAM | Defining constraint of the Bitcask model |
| One writer process per directory | Enforced by lock file |
| No range scans or ordered iteration | Hash index provides point lookups only |
| CRC is not cryptographic | Detects random corruption, not tampering |
| No multi-key atomicity | Each operation is independent |
| Key ≤ 64 KB, value ≤ 16 MB (default) | Format and configuration |
| Values above ~2 GB are impossible | Java array length is an `int` |
| Phase 1 only: unbounded file growth, no compaction, O(log size) startup | Removed in Phases 2–3 |
