# Bitcask-Style Storage Engine

An embedded key-value storage engine in Java, modeled on
[Bitcask](https://riak.com/assets/bitcask-intro.pdf): an append-only log on
disk, a hash index in memory, and crash recovery by replaying the log.

Every write is appended to a single file and never modified again. An in-memory
index maps each live key to the offset of its most recent record, so a read is
always exactly **one disk access**, no matter how large the data grows. The
price of that is the model's defining constraint: **every key must fit in RAM**.

This is a study project. It was designed on paper before a line of code was
written, and the reasoning behind each decision is recorded in
[the design spec](docs/superpowers/specs/2026-08-22-bitcask-core-design.md).

---

## Status

**Phase 1 (core) — nearly complete.** 82 tests passing.

| | |
|---|---|
| ✅ | On-disk record format with CRC32C |
| ✅ | In-memory index (KeyDir) |
| ✅ | `put` / `get` / `delete` |
| ✅ | Crash recovery by log replay, with a corruption policy |
| ✅ | Single-writer enforcement via a directory lock |
| ⬜ | Model-based testing against a reference `HashMap` |
| ⬜ | `kill -9` crash test |

Later phases: segment rotation, compaction, durability tuning, measurement, and
deliberately breaking it. See [Roadmap](#roadmap).

Not intended for production use.

---

## Usage

```java
try (Bitcask db = Bitcask.open(Path.of("/var/data/mystore"), BitcaskConfig.defaults())) {

    db.put("user:42".getBytes(UTF_8), "onur".getBytes(UTF_8));

    byte[] value = db.get("user:42".getBytes(UTF_8));   // "onur", or null

    db.delete("user:42".getBytes(UTF_8));               // true if it was there

    RecoveryReport report = db.recoveryReport();
    if (report.lostData()) {
        log.warn("{} bytes were unreadable at offset {}",
                 report.bytesDiscarded(), report.truncatedAtOffset());
    }
}
```

Keys and values are raw `byte[]`. Serialization is the caller's concern, not
the engine's.

---

## How it works

### On disk

A log file starts with an 8-byte header, followed by records back to back:

```
ONRC 0001 0000   <record> <record> <record> ...
```

Each record is 27 bytes of header plus its payload, big-endian:

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 4 | `crc32c` | Covers **every byte after itself** |
| 4 | 8 | `seq` | Monotonic counter — the sole ordering authority |
| 12 | 8 | `tstamp` | Epoch millis, informational only, never compared |
| 20 | 1 | `type` | 1 = PUT, 2 = TOMBSTONE |
| 21 | 2 | `keyLen` | Unsigned, 1–65535 |
| 23 | 4 | `valLen` | Unsigned |
| 27 | … | `key` | |
| … | … | `value` | |

Here is `put("ad", "onur")` followed by `delete("ad")`, as `xxd` sees it:

```
00000000: 4f4e 5243 0001 0000 94fa 62bc 0000 0000  ONRC......b.....
00000010: 0000 0001 0000 01a0 2a9f a694 0100 0200  ........*.......
00000020: 0000 0461 646f 6e75 7240 8cd1 c200 0000  ...adonur@......
00000030: 0000 0000 0200 0001 a02a 9fa6 9502 0002  .........*......
00000040: 0000 0000 6164                           ....ad
```

The file grew after the delete. Nothing is ever erased in place — reclaiming
that space is what compaction is for.

### Writing

Encode the record, append it, **then** update the index. Never the other way
around: if the write fails, the index must not be left pointing at a record that
does not exist.

### Reading

Look the key up in the index, read the record at that offset, verify the
checksum, and verify that the key in the record is the key that was asked for.

The second check catches something the checksum cannot. A wrong offset lands on
a perfectly intact record belonging to some *other* key — its CRC is valid and
nothing looks wrong. Comparing the key is what turns silent wrong data into a
loud error, and it costs nothing since the bytes are already in hand.

### Recovering

On open, the log is replayed from the start; a later record supersedes an
earlier one, so the physical order in the file is already the correct order.

The interesting part is what happens when a record does not verify:

| Situation | Cause | Response |
|---|---|---|
| Damage **at the end** of the log | A write interrupted by a crash — normal and expected | Truncate, report, open |
| Damage **in the middle** | Bit rot, hardware fault — not normal | Same truncation, but everything after it is lost too |

The two cannot be told apart with certainty, because the length fields of a
corrupt record are themselves suspect. So the choice is the application's:

- `TOLERATE_TAIL` (default) — truncate the damaged tail and **report exactly
  what was discarded**
- `STRICT` — refuse to open, and leave the file untouched so the evidence
  survives

Recovery never fails silently. `RecoveryReport` carries the record count, the
byte count discarded, the truncation offset, and why the scan stopped.

---

## Some decisions worth explaining

**The checksum covers the length fields, not just the payload.** A flipped bit
in `keyLen` would otherwise go unnoticed, the reader would consume the wrong
number of bytes, and every record boundary after it would be lost. One bit would
cost the whole file.

**Ordering uses a sequence number, not a timestamp.** Wall-clock time is not
monotonic: NTP steps it backwards, VMs jump on resume. If a newer record carried
an older timestamp, compaction would discard the *newer* value — silently. A
counter answers "which came later" with certainty, which is the only question
the engine actually asks. The timestamp is still stored, for debugging, and is
never compared anywhere.

**Nothing is allocated before every length field is validated.** A corrupt
length that reaches `new byte[...]` lets a single bad byte take the process down
with an `OutOfMemoryError` — a defect class with CVEs to its name in production
parsers.

**The index points at whole records, not at values.** Pointing at the value
would read fewer bytes but make checksum verification impossible, since the CRC
covers the entire record. The extra bytes come from a page that is being read
anyway.

**Keys are copied on write; values are not.** The index keeps the key, so a
caller reusing its buffer would mutate the array the map is keyed on and orphan
the entry — alive in memory, unreachable in practice, while the record on disk
stayed perfectly correct. The value is written and released, so it needs no copy.

---

## Configuration

```java
new BitcaskConfig(
    16 * 1024 * 1024,           // maxValueSize
    SyncPolicy.NEVER,           // NEVER | ALWAYS
    RecoveryMode.TOLERATE_TAIL  // TOLERATE_TAIL | STRICT
);
```

| Setting | Default | Notes |
|---|---|---|
| `maxValueSize` | 16 MB | Write-side limit only |
| `syncPolicy` | `NEVER` | `ALWAYS` calls fsync on every write |
| `recoveryMode` | `TOLERATE_TAIL` | See above |

**On `SyncPolicy`:** `NEVER` still survives `kill -9`, because the OS page cache
outlives the process. It does not survive power loss. `ALWAYS` survives both, at
roughly 50–100× the cost per write. Phase 4 adds the middle ground.

There is a second, deliberately non-configurable limit: `HARD_MAX_VALUE_SIZE`
(64 MB), used when parsing. If recovery relied on `maxValueSize` instead,
lowering that setting would reclassify already-written records as corrupt — a
configuration change must never invalidate data that was written successfully.

---

## Limits

Knowing what an engine does *not* guarantee matters as much as knowing what it
does.

| Limit | Nature |
|---|---|
| Every key must fit in RAM | Defining constraint of the Bitcask model |
| One writer process per directory | Enforced by a lock file |
| No range scans or ordered iteration | A hash index gives point lookups only |
| CRC is not cryptographic | Detects random corruption, not tampering |
| No multi-key atomicity | Each operation stands alone |
| Key ≤ 64 KB, value ≤ 16 MB by default | Format and configuration |
| Phase 1 only: the log grows without bound | Segment rotation and compaction come next |

Roughly 168 bytes of index memory per key on a 64-bit JVM, measured for a
16-byte key. That works out to about 1.7 GB for 10 million keys — the number to
check before reaching for this design.

---

## Roadmap

| Phase | Content |
|---|---|
| **1** | Core: append-only log, in-memory index, crash recovery |
| 2 | Segment rotation |
| 3 | Compaction and hint files |
| 4 | Durability policies: group commit, write buffering |
| 5 | Measurement: throughput, p99 latency, memory profile |
| 6 | Breaking it: `kill -9`, torn writes, bit rot, disk full |

Phases 5 and 6 are the point of the exercise. Several choices were left
deliberately unoptimized so that the improvements can be justified with numbers
rather than intuition — no write buffer yet, `ByteBuffer` index keys, fsync off
by default.

---

## Building

```bash
mvn test          # run the suite
mvn package       # build the jar
```

Java 21, Maven. No third-party runtime dependencies; JUnit 5 for tests only.

---

## Documentation

- [Design spec](docs/superpowers/specs/2026-08-22-bitcask-core-design.md) — every
  decision, with the reasoning and the alternatives that were rejected
- [Implementation plan](docs/superpowers/plans/2026-08-22-bitcask-core.md) —
  task-by-task breakdown
