# Bitcask Core (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Phase 1 core of a Bitcask-style embedded key-value engine: a single append-only log, an in-memory hash index, `put`/`get`/`delete`, and crash recovery.

**Architecture:** One append-only file (`data.log`) holds every write as a self-framing record with a CRC32C checksum. An in-memory `ConcurrentHashMap` (the KeyDir) maps each live key to its record's offset. Reads are one positional read plus checksum verification. On open, the log is replayed to rebuild the KeyDir; a damaged tail is truncated and reported.

**Tech Stack:** Java 21, Maven, JUnit 5. No third-party runtime dependencies.

**Spec:** `docs/superpowers/specs/2026-08-22-bitcask-core-design.md`

## Global Constraints

- Java 21 (LTS). `java.util.zip.CRC32C` requires Java 9+.
- **No third-party runtime dependencies.** Test scope may use JUnit 5 only.
- Package root: `com.onurcanogul.bitcask`
- Byte order is **big-endian** everywhere.
- Record header is **exactly 27 bytes**; file header is **exactly 8 bytes**.
- Key length: 1–65,535 bytes. `keyLen == 0` is invalid.
- `HARD_MAX_VALUE_SIZE = 64 MB` is a compile-time constant, never configurable.
- Default `maxValueSize = 16 MB`, configurable.
- **Disk before memory:** the KeyDir is updated only after the disk write returns.
- **Never be silent:** any discarded byte is reported through `RecoveryReport`.
- Keys are copied on write; values are not. `get` returns a fresh array.
- All file I/O is positional (`read(buf,pos)` / `write(buf,pos)`); the channel's file pointer is never used.

---

## File Structure

Packages are split by responsibility, not by layer: what the bytes look like on
disk, what lives in memory, what happens at startup, who owns the files.

**`com.onurcanogul.bitcask`** — the public API surface:

| File | Responsibility |
|---|---|
| `Bitcask.java` | `open`, `put`, `get`, `delete`, `close` |
| `BitcaskConfig.java` | Configuration record + defaults |
| `SyncPolicy.java` | enum `NEVER`, `ALWAYS` |
| `RecoveryMode.java` | enum `TOLERATE_TAIL`, `STRICT` |
| `RecoveryReport.java` | What recovery found (returned to the caller) |
| `StopReason.java` | enum: why the scan stopped |

**`...bitcask.format`** — how bytes look on disk:

| File | Responsibility |
|---|---|
| `FormatLimits.java` | `MAX_KEY_SIZE`, `HARD_MAX_VALUE_SIZE` — constants, never configuration |
| `RecordType.java` | enum `PUT(1)`, `TOMBSTONE(2)` |
| `LogRecord.java` | Decoded record value object |
| `RecordCodec.java` | Encode/decode records, CRC32C, header validation |
| `FileHeader.java` | 8-byte file header: write + validate |
| `CorruptRecordException.java` | Thrown when bytes cannot be trusted |

**`...bitcask.index`** — what lives in memory:

| File | Responsibility |
|---|---|
| `KeyDirEntry.java` | fileId, recordPos, recordSize, seq |

**`...bitcask.recovery`** — rebuilding state at startup:

| File | Responsibility |
|---|---|
| `Recovery.java` | Log replay, validation chain, corruption policy |
| `RecoveryResult.java` | Internal: report + endOffset + maxSeq |

**`...bitcask.store`** — file and channel ownership:

| File | Responsibility |
|---|---|
| `DirectoryLock.java` | Single-writer enforcement (file lock + in-JVM guard) |

**Dependency direction is one-way:** the root package depends on `format`,
`index`, `recovery` and `store`; none of them depend back on the root. This is
why `HARD_MAX_VALUE_SIZE` lives in `FormatLimits` rather than `BitcaskConfig` —
`RecordCodec` needs it, and pointing the format package back at the config
package would create a cycle.

**Tests mirror the package structure**, plus `ModelBasedTest.java`,
`CrashTest.java` and `CrashWriterMain.java` in the root test package.

---

## Task 1: Project Skeleton

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`
- Create: `src/test/java/com/onurcanogul/bitcask/SmokeTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: a working `mvn test` cycle on Java 21 with JUnit 5

- [ ] **Step 1: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.onurcanogul</groupId>
  <artifactId>bitcask</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <junit.version>5.11.3</junit.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.2</version>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Write `.gitignore`**

```
target/
*.class
.idea/workspace.xml
*.iml
```

- [ ] **Step 3: Write the smoke test**

```java
package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SmokeTest {
    @Test
    void junitAndJava21Work() {
        assertEquals(21, Runtime.version().feature());
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -q test`
Expected: PASS, 1 test.

- [ ] **Step 5: Commit**

```bash
git add pom.xml .gitignore src/
git commit -m "chore: maven skeleton with junit 5 on java 21"
```

---

## Task 2: Value Types and Constants

**Files:**
- Create: `src/main/java/com/onurcanogul/bitcask/RecordType.java`
- Create: `src/main/java/com/onurcanogul/bitcask/SyncPolicy.java`
- Create: `src/main/java/com/onurcanogul/bitcask/RecoveryMode.java`
- Create: `src/main/java/com/onurcanogul/bitcask/BitcaskConfig.java`
- Create: `src/main/java/com/onurcanogul/bitcask/LogRecord.java`
- Create: `src/main/java/com/onurcanogul/bitcask/KeyDirEntry.java`
- Test: `src/test/java/com/onurcanogul/bitcask/BitcaskConfigTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `RecordType.PUT` (code `1`), `RecordType.TOMBSTONE` (code `2`), `byte code()`, `static RecordType fromCode(byte) throws CorruptRecordException`
  - `SyncPolicy.NEVER`, `SyncPolicy.ALWAYS`
  - `RecoveryMode.TOLERATE_TAIL`, `RecoveryMode.STRICT`
  - `BitcaskConfig(int maxValueSize, SyncPolicy syncPolicy, RecoveryMode recoveryMode)` + `static BitcaskConfig defaults()`
  - `LogRecord(long seq, long tstamp, RecordType type, byte[] key, byte[] value)`
  - `KeyDirEntry(int fileId, long recordPos, int recordSize, long seq)`

- [ ] **Step 1: Write the failing test**

```java
package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BitcaskConfigTest {

    @Test
    void defaultsMatchTheSpec() {
        BitcaskConfig c = BitcaskConfig.defaults();
        assertEquals(16 * 1024 * 1024, c.maxValueSize());
        assertEquals(SyncPolicy.NEVER, c.syncPolicy());
        assertEquals(RecoveryMode.TOLERATE_TAIL, c.recoveryMode());
    }

    @Test
    void maxValueSizeCannotExceedHardLimit() {
        assertThrows(IllegalArgumentException.class,
            () -> new BitcaskConfig(65 * 1024 * 1024, SyncPolicy.NEVER, RecoveryMode.TOLERATE_TAIL));
    }

    @Test
    void maxValueSizeMustBePositive() {
        assertThrows(IllegalArgumentException.class,
            () -> new BitcaskConfig(0, SyncPolicy.NEVER, RecoveryMode.TOLERATE_TAIL));
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvn -q test -Dtest=BitcaskConfigTest`
Expected: compilation failure — the types do not exist yet.

- [ ] **Step 3: Write the enums and records**

```java
// RecordType.java
package com.onurcanogul.bitcask;

public enum RecordType {
    PUT((byte) 1),
    TOMBSTONE((byte) 2);

    private final byte code;

    RecordType(byte code) { this.code = code; }

    public byte code() { return code; }

    public static RecordType fromCode(byte code) throws CorruptRecordException {
        for (RecordType t : values()) {
            if (t.code == code) return t;
        }
        throw new CorruptRecordException("unknown record type: " + code);
    }
}
```

```java
// SyncPolicy.java
package com.onurcanogul.bitcask;

/** NEVER: rely on the OS page cache. ALWAYS: fsync on every write. */
public enum SyncPolicy { NEVER, ALWAYS }
```

```java
// RecoveryMode.java
package com.onurcanogul.bitcask;

/** TOLERATE_TAIL: truncate a damaged tail and report it. STRICT: refuse to open. */
public enum RecoveryMode { TOLERATE_TAIL, STRICT }
```

```java
// BitcaskConfig.java
package com.onurcanogul.bitcask;

public record BitcaskConfig(int maxValueSize, SyncPolicy syncPolicy, RecoveryMode recoveryMode) {

    /** Parsing robustness limit. Never configurable: a config change must not
     *  invalidate a record that was already written successfully. */
    public static final int HARD_MAX_VALUE_SIZE = 64 * 1024 * 1024;

    public static final int DEFAULT_MAX_VALUE_SIZE = 16 * 1024 * 1024;

    public BitcaskConfig {
        if (maxValueSize <= 0) {
            throw new IllegalArgumentException("maxValueSize must be positive: " + maxValueSize);
        }
        if (maxValueSize > HARD_MAX_VALUE_SIZE) {
            throw new IllegalArgumentException(
                "maxValueSize " + maxValueSize + " exceeds HARD_MAX_VALUE_SIZE " + HARD_MAX_VALUE_SIZE);
        }
    }

    public static BitcaskConfig defaults() {
        return new BitcaskConfig(DEFAULT_MAX_VALUE_SIZE, SyncPolicy.NEVER, RecoveryMode.TOLERATE_TAIL);
    }
}
```

```java
// LogRecord.java
package com.onurcanogul.bitcask;

/** A decoded record. Arrays are not defensively copied here; callers own them. */
public record LogRecord(long seq, long tstamp, RecordType type, byte[] key, byte[] value) {}
```

```java
// KeyDirEntry.java
package com.onurcanogul.bitcask;

/** Points at the whole record, not just the value, so every read can verify the CRC. */
public record KeyDirEntry(int fileId, long recordPos, int recordSize, long seq) {}
```

```java
// CorruptRecordException.java
package com.onurcanogul.bitcask;

import java.io.IOException;

public class CorruptRecordException extends IOException {
    public CorruptRecordException(String message) { super(message); }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -q test -Dtest=BitcaskConfigTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/onurcanogul/bitcask src/test/java/com/onurcanogul/bitcask/BitcaskConfigTest.java
git commit -m "feat: value types, enums and config with hard limits"
```

---

## Task 3: Record Codec (encode/decode + CRC)

This is the heart of the format. Everything else depends on it.

Note: the code blocks below were written before the package split. Use the
package declarations from the File Structure section — `RecordCodec`,
`FileHeader` and the record types live in `com.onurcanogul.bitcask.format`,
`DirectoryLock` in `...store`, `Recovery` and `RecoveryResult` in
`...recovery`, and everything else in the root package.

**Files:**
- Create: `src/main/java/com/onurcanogul/bitcask/format/RecordCodec.java`
- Test: `src/test/java/com/onurcanogul/bitcask/format/RecordCodecTest.java`

**Interfaces:**
- Consumes: `LogRecord`, `RecordType`, `CorruptRecordException`, `FormatLimits`
- Produces:
  - `RecordCodec.HEADER_SIZE` = `27`
  - key length bound comes from `FormatLimits.MAX_KEY_SIZE`
  - `static ByteBuffer encode(long seq, long tstamp, RecordType type, byte[] key, byte[] value)`
  - `static int recordSize(int keyLen, int valLen)`
  - `static LogRecord decode(ByteBuffer record) throws CorruptRecordException` — buffer must contain exactly one full record starting at position 0
  - `static void validateHeaderFields(byte typeCode, int keyLen, long valLen, long bytesRemainingInFile) throws CorruptRecordException`

- [ ] **Step 1: Write the failing tests**

```java
package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class RecordCodecTest {

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void headerIsExactly27Bytes() {
        assertEquals(27, RecordCodec.HEADER_SIZE);
    }

    @Test
    void roundTripPreservesEverything() throws Exception {
        ByteBuffer buf = RecordCodec.encode(7L, 1234L, RecordType.PUT, bytes("user:42"), bytes("hello"));
        LogRecord r = RecordCodec.decode(buf);

        assertEquals(7L, r.seq());
        assertEquals(1234L, r.tstamp());
        assertEquals(RecordType.PUT, r.type());
        assertArrayEquals(bytes("user:42"), r.key());
        assertArrayEquals(bytes("hello"), r.value());
    }

    @Test
    void encodedSizeIsHeaderPlusPayload() {
        ByteBuffer buf = RecordCodec.encode(1L, 1L, RecordType.PUT, bytes("ab"), bytes("xyz"));
        assertEquals(27 + 2 + 3, buf.remaining());
        assertEquals(27 + 2 + 3, RecordCodec.recordSize(2, 3));
    }

    @Test
    void emptyValueIsValid() throws Exception {
        ByteBuffer buf = RecordCodec.encode(1L, 1L, RecordType.PUT, bytes("k"), new byte[0]);
        LogRecord r = RecordCodec.decode(buf);
        assertEquals(0, r.value().length);
        assertEquals(RecordType.PUT, r.type());
    }

    @Test
    void tombstoneRoundTrips() throws Exception {
        ByteBuffer buf = RecordCodec.encode(9L, 1L, RecordType.TOMBSTONE, bytes("k"), new byte[0]);
        LogRecord r = RecordCodec.decode(buf);
        assertEquals(RecordType.TOMBSTONE, r.type());
    }

    @Test
    void flippedBitInValueIsDetected() {
        ByteBuffer buf = RecordCodec.encode(1L, 1L, RecordType.PUT, bytes("k"), bytes("value"));
        buf.put(buf.limit() - 1, (byte) (buf.get(buf.limit() - 1) ^ 0x01));
        assertThrows(CorruptRecordException.class, () -> RecordCodec.decode(buf));
    }

    @Test
    void flippedBitInKeyLengthIsDetected() {
        // keyLen lives at offset 21; corrupting it must be caught because the CRC covers it
        ByteBuffer buf = RecordCodec.encode(1L, 1L, RecordType.PUT, bytes("key"), bytes("value"));
        buf.put(22, (byte) (buf.get(22) ^ 0x01));
        assertThrows(CorruptRecordException.class, () -> RecordCodec.decode(buf));
    }

    @Test
    void keyLongerThan32kRoundTripsCorrectly() throws Exception {
        // Guards the signed/unsigned bug: readShort() would return a negative number here
        byte[] bigKey = new byte[40_000];
        for (int i = 0; i < bigKey.length; i++) bigKey[i] = (byte) i;

        ByteBuffer buf = RecordCodec.encode(1L, 1L, RecordType.PUT, bigKey, bytes("v"));
        LogRecord r = RecordCodec.decode(buf);
        assertArrayEquals(bigKey, r.key());
    }

    @Test
    void emptyKeyIsRejectedOnEncode() {
        assertThrows(IllegalArgumentException.class,
            () -> RecordCodec.encode(1L, 1L, RecordType.PUT, new byte[0], bytes("v")));
    }

    @Test
    void keyAboveMaxIsRejectedOnEncode() {
        assertThrows(IllegalArgumentException.class,
            () -> RecordCodec.encode(1L, 1L, RecordType.PUT, new byte[65_536], bytes("v")));
    }

    @Test
    void headerValidationRejectsZeroKeyLength() {
        assertThrows(CorruptRecordException.class,
            () -> RecordCodec.validateHeaderFields((byte) 1, 0, 10, 1_000));
    }

    @Test
    void headerValidationRejectsUnknownType() {
        assertThrows(CorruptRecordException.class,
            () -> RecordCodec.validateHeaderFields((byte) 9, 5, 10, 1_000));
    }

    @Test
    void headerValidationRejectsRecordLongerThanRemainingFile() {
        assertThrows(CorruptRecordException.class,
            () -> RecordCodec.validateHeaderFields((byte) 1, 5, 10_000, 100));
    }

    @Test
    void headerValidationRejectsValueAboveHardLimit() {
        assertThrows(CorruptRecordException.class,
            () -> RecordCodec.validateHeaderFields((byte) 1, 5, 65L * 1024 * 1024, Long.MAX_VALUE));
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -q test -Dtest=RecordCodecTest`
Expected: compilation failure — `RecordCodec` does not exist.

- [ ] **Step 3: Implement `RecordCodec`**

```java
package com.onurcanogul.bitcask;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

/**
 * On-disk record format (big-endian):
 *
 *   offset  size  field
 *        0     4  crc32c    checksum of every byte after itself
 *        4     8  seq       monotonic sequence number (sole ordering authority)
 *       12     8  tstamp    epoch millis, informational only, never compared
 *       20     1  type      1 = PUT, 2 = TOMBSTONE
 *       21     2  keyLen    unsigned, 1..65535
 *       23     4  valLen    unsigned
 *       27   ...  key
 *      ...   ...  value
 */
public final class RecordCodec {

    public static final int HEADER_SIZE = 27;

    private static final int OFF_CRC     = 0;
    private static final int OFF_SEQ     = 4;
    private static final int OFF_TSTAMP  = 12;
    private static final int OFF_TYPE    = 20;
    private static final int OFF_KEY_LEN = 21;
    private static final int OFF_VAL_LEN = 23;

    private RecordCodec() {}

    public static int recordSize(int keyLen, int valLen) {
        return HEADER_SIZE + keyLen + valLen;
    }

    public static ByteBuffer encode(long seq, long tstamp, RecordType type, byte[] key, byte[] value) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("key must not be empty");
        }
        if (key.length > MAX_KEY_SIZE) {
            throw new IllegalArgumentException("key too long: " + key.length + " > " + MAX_KEY_SIZE);
        }
        byte[] val = (value == null) ? new byte[0] : value;

        ByteBuffer buf = ByteBuffer.allocate(recordSize(key.length, val.length))
                                   .order(ByteOrder.BIG_ENDIAN);

        buf.position(OFF_SEQ);
        buf.putLong(seq);
        buf.putLong(tstamp);
        buf.put(type.code());
        buf.putShort((short) key.length);
        buf.putInt(val.length);
        buf.put(key);
        buf.put(val);

        // CRC covers everything after the CRC field itself — length fields included,
        // because they decide what the reader does next.
        CRC32C crc = new CRC32C();
        ByteBuffer crcView = buf.duplicate();
        crcView.position(4);
        crcView.limit(buf.capacity());
        crc.update(crcView);
        buf.putInt(OFF_CRC, (int) crc.getValue());

        buf.position(0);
        buf.limit(buf.capacity());
        return buf;
    }

    /**
     * Validates the header fields that determine how many bytes to read.
     * Runs before any allocation: a corrupt length must never size an array.
     */
    public static void validateHeaderFields(byte typeCode, int keyLen, long valLen,
                                            long bytesRemainingInFile) throws CorruptRecordException {
        if (keyLen <= 0 || keyLen > MAX_KEY_SIZE) {
            throw new CorruptRecordException("invalid keyLen: " + keyLen);
        }
        RecordType.fromCode(typeCode); // throws on unknown type
        if (valLen < 0 || valLen > FormatLimits.HARD_MAX_VALUE_SIZE) {
            throw new CorruptRecordException("invalid valLen: " + valLen);
        }
        long total = (long) HEADER_SIZE + keyLen + valLen;
        if (total > bytesRemainingInFile) {
            throw new CorruptRecordException(
                "record claims " + total + " bytes but only " + bytesRemainingInFile + " remain");
        }
    }

    /** Decodes a buffer holding exactly one full record starting at position 0. */
    public static LogRecord decode(ByteBuffer record) throws CorruptRecordException {
        ByteBuffer buf = record.duplicate().order(ByteOrder.BIG_ENDIAN);
        buf.position(0);

        if (buf.remaining() < HEADER_SIZE) {
            throw new CorruptRecordException("buffer shorter than header: " + buf.remaining());
        }

        int storedCrc = buf.getInt(OFF_CRC);
        long seq      = buf.getLong(OFF_SEQ);
        long tstamp   = buf.getLong(OFF_TSTAMP);
        byte typeCode = buf.get(OFF_TYPE);
        int keyLen    = buf.getShort(OFF_KEY_LEN) & 0xFFFF;        // unsigned
        long valLen   = buf.getInt(OFF_VAL_LEN) & 0xFFFFFFFFL;     // unsigned

        validateHeaderFields(typeCode, keyLen, valLen, buf.limit());

        int total = HEADER_SIZE + keyLen + (int) valLen;
        if (buf.limit() < total) {
            throw new CorruptRecordException("buffer holds " + buf.limit() + " bytes, record needs " + total);
        }

        CRC32C crc = new CRC32C();
        ByteBuffer crcView = buf.duplicate();
        crcView.limit(total);
        crcView.position(4);
        crc.update(crcView);
        if ((int) crc.getValue() != storedCrc) {
            throw new CorruptRecordException("crc mismatch at seq " + seq);
        }

        byte[] key = new byte[keyLen];
        byte[] value = new byte[(int) valLen];
        buf.position(HEADER_SIZE).get(key).get(value);

        return new LogRecord(seq, tstamp, RecordType.fromCode(typeCode), key, value);
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `mvn -q test -Dtest=RecordCodecTest`
Expected: PASS, 14 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/onurcanogul/bitcask/RecordCodec.java src/test/java/com/onurcanogul/bitcask/RecordCodecTest.java
git commit -m "feat: record codec with crc32c over all length fields"
```

---

## Task 4: File Header

**Files:**
- Create: `src/main/java/com/onurcanogul/bitcask/format/FileHeader.java`
- Test: `src/test/java/com/onurcanogul/bitcask/format/FileHeaderTest.java`

**Interfaces:**
- Consumes: `CorruptRecordException`
- Produces:
  - `FileHeader.SIZE` = `8`, `FileHeader.MAGIC` = `0x4F4E5243`, `FileHeader.VERSION` = `(short) 1`
  - `static void write(FileChannel ch) throws IOException` — writes at offset 0
  - `static void validate(FileChannel ch) throws IOException` — throws on bad magic/version

- [ ] **Step 1: Write the failing test**

```java
package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

class FileHeaderTest {

    @TempDir Path dir;

    private FileChannel open(Path p) throws IOException {
        return FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    @Test
    void headerIs8Bytes() {
        assertEquals(8, FileHeader.SIZE);
    }

    @Test
    void writtenHeaderValidates() throws Exception {
        Path p = dir.resolve("data.log");
        try (FileChannel ch = open(p)) {
            FileHeader.write(ch);
            FileHeader.validate(ch);   // must not throw
            assertEquals(8, ch.size());
        }
    }

    @Test
    void wrongMagicIsRejected() throws Exception {
        Path p = dir.resolve("data.log");
        try (FileChannel ch = open(p)) {
            ch.write(ByteBuffer.wrap(new byte[] {'J', 'U', 'N', 'K', 0, 1, 0, 0}), 0);
            assertThrows(IOException.class, () -> FileHeader.validate(ch));
        }
    }

    @Test
    void unknownVersionIsRejected() throws Exception {
        Path p = dir.resolve("data.log");
        try (FileChannel ch = open(p)) {
            ch.write(ByteBuffer.wrap(new byte[] {'O', 'N', 'R', 'C', 0, 99, 0, 0}), 0);
            assertThrows(IOException.class, () -> FileHeader.validate(ch));
        }
    }

    @Test
    void truncatedHeaderIsRejected() throws Exception {
        Path p = dir.resolve("data.log");
        try (FileChannel ch = open(p)) {
            ch.write(ByteBuffer.wrap(new byte[] {'O', 'N'}), 0);
            assertThrows(IOException.class, () -> FileHeader.validate(ch));
        }
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -q test -Dtest=FileHeaderTest`
Expected: compilation failure.

- [ ] **Step 3: Implement `FileHeader`**

```java
package com.onurcanogul.bitcask;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/** 8-byte file header: magic(4) + version(2) + reserved(2). */
public final class FileHeader {

    public static final int SIZE = 8;
    public static final int MAGIC = 0x4F4E5243; // "ONRC"
    public static final short VERSION = 1;

    private FileHeader() {}

    public static void write(FileChannel ch) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(MAGIC).putShort(VERSION).putShort((short) 0).flip();
        while (buf.hasRemaining()) {
            ch.write(buf, SIZE - buf.remaining());
        }
    }

    public static void validate(FileChannel ch) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN);
        int read = 0;
        while (read < SIZE) {
            int n = ch.read(buf, read);
            if (n < 0) break;
            read += n;
        }
        if (read < SIZE) {
            throw new IOException("file header truncated: " + read + " bytes");
        }
        buf.flip();
        int magic = buf.getInt();
        short version = buf.getShort();
        if (magic != MAGIC) {
            throw new IOException(String.format("not a bitcask file: magic=0x%08X", magic));
        }
        if (version != VERSION) {
            throw new IOException("unsupported format version: " + version);
        }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `mvn -q test -Dtest=FileHeaderTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/onurcanogul/bitcask/FileHeader.java src/test/java/com/onurcanogul/bitcask/FileHeaderTest.java
git commit -m "feat: file header with magic and format version"
```

---

## Task 5: Directory Lock (single-writer enforcement)

**Files:**
- Create: `src/main/java/com/onurcanogul/bitcask/store/DirectoryLock.java`
- Test: `src/test/java/com/onurcanogul/bitcask/store/DirectoryLockTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `static DirectoryLock acquire(Path dir) throws IOException` — throws `IOException` if already held
  - `void release() throws IOException` — idempotent

- [ ] **Step 1: Write the failing test**

```java
package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DirectoryLockTest {

    @TempDir Path dir;

    @Test
    void secondAcquireInSameJvmFails() throws Exception {
        DirectoryLock first = DirectoryLock.acquire(dir);
        assertThrows(IOException.class, () -> DirectoryLock.acquire(dir));
        first.release();
    }

    @Test
    void lockCanBeReacquiredAfterRelease() throws Exception {
        DirectoryLock first = DirectoryLock.acquire(dir);
        first.release();
        DirectoryLock second = DirectoryLock.acquire(dir);
        second.release();
    }

    @Test
    void releaseIsIdempotent() throws Exception {
        DirectoryLock lock = DirectoryLock.acquire(dir);
        lock.release();
        lock.release(); // must not throw
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -q test -Dtest=DirectoryLockTest`
Expected: compilation failure.

- [ ] **Step 3: Implement `DirectoryLock`**

```java
package com.onurcanogul.bitcask;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the single-writer-per-directory rule.
 *
 * FileLock guards against other processes. It does NOT guard against a second
 * open inside the same JVM, so an in-process registry covers that case.
 */
public final class DirectoryLock implements AutoCloseable {

    public static final String LOCK_FILE_NAME = "bitcask.lock";

    private static final Set<Path> OPEN_DIRECTORIES = ConcurrentHashMap.newKeySet();

    private final Path canonicalDir;
    private final FileChannel channel;
    private final FileLock lock;
    private volatile boolean released;

    private DirectoryLock(Path canonicalDir, FileChannel channel, FileLock lock) {
        this.canonicalDir = canonicalDir;
        this.channel = channel;
        this.lock = lock;
    }

    public static DirectoryLock acquire(Path dir) throws IOException {
        Path canonical = dir.toRealPath();

        if (!OPEN_DIRECTORIES.add(canonical)) {
            throw new IOException("directory already open in this JVM: " + canonical);
        }

        FileChannel channel = null;
        try {
            channel = FileChannel.open(canonical.resolve(LOCK_FILE_NAME),
                                       StandardOpenOption.CREATE,
                                       StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("directory locked by another process: " + canonical);
            }
            return new DirectoryLock(canonical, channel, lock);
        } catch (OverlappingFileLockException e) {
            closeQuietly(channel);
            OPEN_DIRECTORIES.remove(canonical);
            throw new IOException("directory locked by this JVM: " + canonical, e);
        } catch (IOException e) {
            closeQuietly(channel);
            OPEN_DIRECTORIES.remove(canonical);
            throw e;
        }
    }

    public void release() throws IOException {
        if (released) return;
        released = true;
        try {
            if (lock.isValid()) lock.release();
        } finally {
            OPEN_DIRECTORIES.remove(canonicalDir);
            channel.close();
        }
    }

    @Override
    public void close() throws IOException { release(); }

    private static void closeQuietly(FileChannel ch) {
        if (ch == null) return;
        try { ch.close(); } catch (IOException ignored) { }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `mvn -q test -Dtest=DirectoryLockTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/onurcanogul/bitcask/DirectoryLock.java src/test/java/com/onurcanogul/bitcask/DirectoryLockTest.java
git commit -m "feat: directory lock enforcing one writer per directory"
```

---

## Task 6: Engine Skeleton — open/close on an empty directory

**Files:**
- Create: `src/main/java/com/onurcanogul/bitcask/RecoveryReport.java`
- Create: `src/main/java/com/onurcanogul/bitcask/StopReason.java`
- Create: `src/main/java/com/onurcanogul/bitcask/Bitcask.java`
- Test: `src/test/java/com/onurcanogul/bitcask/BitcaskLifecycleTest.java`

**Interfaces:**
- Consumes: `FileHeader`, `DirectoryLock`, `BitcaskConfig`, `KeyDirEntry`
- Produces:
  - `StopReason.CLEAN_EOF`, `CRC_MISMATCH`, `INVALID_HEADER_FIELD`, `NON_INCREASING_SEQ`, `SHORT_READ`
  - `RecoveryReport(long recordsReplayed, int liveKeys, long bytesDiscarded, long truncatedAtOffset, StopReason reason)`
  - `static Bitcask open(Path dir, BitcaskConfig config) throws IOException`
  - `RecoveryReport recoveryReport()`
  - `void close() throws IOException` (idempotent)
  - `int size()` — number of live keys

- [ ] **Step 1: Write the failing test**

```java
package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BitcaskLifecycleTest {

    @TempDir Path dir;

    @Test
    void opensEmptyDirectoryAndCreatesLog() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertEquals(0, db.size());
            assertTrue(Files.exists(dir.resolve("data.log")));
            RecoveryReport r = db.recoveryReport();
            assertEquals(0, r.recordsReplayed());
            assertEquals(0, r.bytesDiscarded());
            assertEquals(StopReason.CLEAN_EOF, r.reason());
        }
    }

    @Test
    void secondOpenOfSameDirectoryFails() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertThrows(IOException.class, () -> Bitcask.open(dir, BitcaskConfig.defaults()));
        }
    }

    @Test
    void closeIsIdempotent() throws Exception {
        Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults());
        db.close();
        db.close();
    }

    @Test
    void operationsOnClosedEngineThrow() throws Exception {
        Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults());
        db.close();
        assertThrows(IllegalStateException.class, () -> db.get("k".getBytes()));
    }

    @Test
    void reopenAfterCloseSucceeds() throws Exception {
        Bitcask.open(dir, BitcaskConfig.defaults()).close();
        Bitcask.open(dir, BitcaskConfig.defaults()).close();
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -q test -Dtest=BitcaskLifecycleTest`
Expected: compilation failure.

- [ ] **Step 3: Implement the skeleton**

```java
// StopReason.java
package com.onurcanogul.bitcask;

/** Why the recovery scan stopped. */
public enum StopReason {
    CLEAN_EOF,
    CRC_MISMATCH,
    INVALID_HEADER_FIELD,
    NON_INCREASING_SEQ,
    SHORT_READ
}
```

```java
// RecoveryReport.java
package com.onurcanogul.bitcask;

/**
 * What recovery found. bytesDiscarded > 0 means data was lost — the engine
 * states it plainly rather than resolving it on the application's behalf.
 */
public record RecoveryReport(long recordsReplayed,
                             int liveKeys,
                             long bytesDiscarded,
                             long truncatedAtOffset,
                             StopReason reason) {

    public boolean lostData() { return bytesDiscarded > 0; }
}
```

```java
// Bitcask.java  (skeleton — put/get/delete arrive in Tasks 7-9)
package com.onurcanogul.bitcask;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Bitcask implements AutoCloseable {

    public static final String DATA_FILE_NAME = "data.log";

    private final BitcaskConfig config;
    private final DirectoryLock lock;
    private final FileChannel channel;
    private final Map<ByteBuffer, KeyDirEntry> keyDir = new ConcurrentHashMap<>();
    private final RecoveryReport report;

    private long writePos;
    private long nextSeq;
    private volatile boolean closed;

    private Bitcask(BitcaskConfig config, DirectoryLock lock, FileChannel channel,
                    RecoveryReport report, long writePos, long nextSeq) {
        this.config = config;
        this.lock = lock;
        this.channel = channel;
        this.report = report;
        this.writePos = writePos;
        this.nextSeq = nextSeq;
    }

    public static Bitcask open(Path dir, BitcaskConfig config) throws IOException {
        Files.createDirectories(dir);
        DirectoryLock lock = DirectoryLock.acquire(dir);
        FileChannel channel = null;
        try {
            Path data = dir.resolve(DATA_FILE_NAME);
            boolean fresh = !Files.exists(data) || Files.size(data) == 0;

            channel = FileChannel.open(data,
                                       StandardOpenOption.CREATE,
                                       StandardOpenOption.READ,
                                       StandardOpenOption.WRITE);

            if (fresh) {
                FileHeader.write(channel);
            } else {
                FileHeader.validate(channel);
            }

            // Task 8 replaces this with a real replay.
            RecoveryReport report = new RecoveryReport(0, 0, 0, -1, StopReason.CLEAN_EOF);
            return new Bitcask(config, lock, channel, report, channel.size(), 1L);
        } catch (IOException | RuntimeException e) {
            if (channel != null) {
                try { channel.close(); } catch (IOException ignored) { }
            }
            lock.release();
            throw e;
        }
    }

    public RecoveryReport recoveryReport() { return report; }

    public int size() {
        ensureOpen();
        return keyDir.size();
    }

    public byte[] get(byte[] key) throws IOException {
        ensureOpen();
        throw new UnsupportedOperationException("Task 8");
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            channel.close();
        } finally {
            lock.release();
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("engine is closed");
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `mvn -q test -Dtest=BitcaskLifecycleTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/onurcanogul/bitcask src/test/java/com/onurcanogul/bitcask/BitcaskLifecycleTest.java
git commit -m "feat: engine lifecycle with lock, file header and recovery report"
```

---

## Task 7: Write Path (`put`)

**Files:**
- Modify: `src/main/java/com/onurcanogul/bitcask/Bitcask.java`
- Test: `src/test/java/com/onurcanogul/bitcask/BitcaskWriteTest.java`

**Interfaces:**
- Consumes: `RecordCodec.encode`, `KeyDirEntry`, `SyncPolicy`
- Produces:
  - `void put(byte[] key, byte[] value) throws IOException`
  - private `ByteBuffer keyOf(byte[] key)` — copies the key, then wraps it

- [ ] **Step 1: Write the failing test**

```java
package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BitcaskWriteTest {

    @TempDir Path dir;

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void putGrowsTheLogAndTheIndex() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            long before = Files.size(dir.resolve("data.log"));
            db.put(b("k"), b("v"));
            long after = Files.size(dir.resolve("data.log"));

            assertEquals(before + 27 + 1 + 1, after);
            assertEquals(1, db.size());
        }
    }

    @Test
    void overwritingAKeyKeepsIndexSizeAtOne() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("v1"));
            db.put(b("k"), b("v2"));
            assertEquals(1, db.size());
        }
    }

    @Test
    void mutatingTheCallersKeyAfterPutDoesNotCorruptTheIndex() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            byte[] key = b("stable");
            db.put(key, b("v"));
            key[0] = 'X';                       // caller reuses its buffer
            assertEquals(1, db.size());
            assertNotNull(db.get(b("stable"))); // still findable
        }
    }

    @Test
    void emptyKeyIsRejected() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertThrows(IllegalArgumentException.class, () -> db.put(new byte[0], b("v")));
        }
    }

    @Test
    void nullKeyIsRejected() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertThrows(IllegalArgumentException.class, () -> db.put(null, b("v")));
        }
    }

    @Test
    void valueAboveConfiguredLimitIsRejected() throws Exception {
        BitcaskConfig small = new BitcaskConfig(1024, SyncPolicy.NEVER, RecoveryMode.TOLERATE_TAIL);
        try (Bitcask db = Bitcask.open(dir, small)) {
            assertThrows(IllegalArgumentException.class, () -> db.put(b("k"), new byte[2048]));
        }
    }

    @Test
    void alwaysSyncPolicyStillWrites() throws Exception {
        BitcaskConfig sync = new BitcaskConfig(BitcaskConfig.DEFAULT_MAX_VALUE_SIZE,
                                               SyncPolicy.ALWAYS, RecoveryMode.TOLERATE_TAIL);
        try (Bitcask db = Bitcask.open(dir, sync)) {
            db.put(b("k"), b("v"));
            assertEquals(1, db.size());
        }
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -q test -Dtest=BitcaskWriteTest`
Expected: FAIL — `put` does not exist.

- [ ] **Step 3: Add `put` to `Bitcask`**

Add these members; `put` is `synchronized` because the spec allows exactly one writer.

```java
    /** Single writer: serialized here. Readers never take this lock. */
    public synchronized void put(byte[] key, byte[] value) throws IOException {
        ensureOpen();
        validateKey(key);
        byte[] val = (value == null) ? new byte[0] : value;
        if (val.length > config.maxValueSize()) {
            throw new IllegalArgumentException(
                "value too large: " + val.length + " > " + config.maxValueSize());
        }

        long seq = nextSeq;
        ByteBuffer record = RecordCodec.encode(seq, System.currentTimeMillis(),
                                               RecordType.PUT, key, val);
        int size = record.remaining();
        long pos = writePos;

        writeFully(record, pos);
        if (config.syncPolicy() == SyncPolicy.ALWAYS) {
            channel.force(false);
        }

        // Disk before memory: only now is the index allowed to point here.
        keyDir.put(keyOf(key), new KeyDirEntry(0, pos, size, seq));
        writePos = pos + size;
        nextSeq = seq + 1;
    }

    private void writeFully(ByteBuffer buf, long position) throws IOException {
        long p = position;
        while (buf.hasRemaining()) {
            p += channel.write(buf, p);
        }
    }

    /** Keys are copied: the engine must not depend on caller discipline. */
    private static ByteBuffer keyOf(byte[] key) {
        return ByteBuffer.wrap(java.util.Arrays.copyOf(key, key.length));
    }

    private static void validateKey(byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("key must not be empty");
        }
        if (key.length > FormatLimits.MAX_KEY_SIZE) {
            throw new IllegalArgumentException(
                "key too long: " + key.length + " > " + FormatLimits.MAX_KEY_SIZE);
        }
    }
```

- [ ] **Step 4: Run the tests**

Run: `mvn -q test -Dtest=BitcaskWriteTest`
Expected: 6 of 7 pass; `mutatingTheCallersKeyAfterPutDoesNotCorruptTheIndex` still fails because `get` is unimplemented. That is expected — Task 8 makes it pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/onurcanogul/bitcask/Bitcask.java src/test/java/com/onurcanogul/bitcask/BitcaskWriteTest.java
git commit -m "feat: write path with key copying and disk-before-memory ordering"
```

---

## Task 8: Read Path (`get`)

**Files:**
- Modify: `src/main/java/com/onurcanogul/bitcask/Bitcask.java`
- Test: `src/test/java/com/onurcanogul/bitcask/BitcaskReadTest.java`

**Interfaces:**
- Consumes: `RecordCodec.decode`, `KeyDirEntry`
- Produces: `byte[] get(byte[] key) throws IOException` — replaces the Task 6 stub

- [ ] **Step 1: Write the failing test**

```java
package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

class BitcaskReadTest {

    @TempDir Path dir;

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void getReturnsWhatPutStored() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("hello"));
            assertArrayEquals(b("hello"), db.get(b("k")));
        }
    }

    @Test
    void getFindsKeyByValueNotByReference() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("user:42"), b("v"));
            byte[] lookalike = b("user:42");   // deliberately a different array object
            assertNotSame(lookalike, b("user:42"));
            assertArrayEquals(b("v"), db.get(lookalike));
        }
    }

    @Test
    void missingKeyReturnsNull() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertNull(db.get(b("nope")));
        }
    }

    @Test
    void latestWriteWins() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("v1"));
            db.put(b("k"), b("v2"));
            assertArrayEquals(b("v2"), db.get(b("k")));
        }
    }

    @Test
    void emptyValueRoundTrips() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), new byte[0]);
            assertArrayEquals(new byte[0], db.get(b("k")));
        }
    }

    @Test
    void returnedArrayIsNotShared() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("value"));
            byte[] first = db.get(b("k"));
            first[0] = 'X';
            assertArrayEquals(b("value"), db.get(b("k")));
        }
    }

    @Test
    void corruptedByteOnDiskIsDetectedOnRead() throws Exception {
        Path data = dir.resolve("data.log");
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("value"));
        }
        // flip a bit inside the stored value
        try (FileChannel ch = FileChannel.open(data, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long pos = ch.size() - 1;
            ByteBuffer one = ByteBuffer.allocate(1);
            ch.read(one, pos);
            one.flip();
            byte flipped = (byte) (one.get(0) ^ 0x01);
            ch.write(ByteBuffer.wrap(new byte[] { flipped }), pos);
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            // The record is corrupt; reading it must throw rather than return bad bytes.
            assertThrows(IOException.class, () -> db.get(b("k")));
        }
    }
}
```

Note: the last test depends on Task 9's recovery keeping the record in the KeyDir. Until Task 9 lands, run it as `@Disabled` or expect it to fail; re-enable it at the end of Task 9.

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -q test -Dtest=BitcaskReadTest`
Expected: FAIL — `get` throws `UnsupportedOperationException`.

- [ ] **Step 3: Implement `get`**

```java
    public byte[] get(byte[] key) throws IOException {
        ensureOpen();
        validateKey(key);

        KeyDirEntry entry = keyDir.get(ByteBuffer.wrap(key));
        if (entry == null) return null;

        ByteBuffer buf = ByteBuffer.allocate(entry.recordSize());
        readFully(buf, entry.recordPos());
        buf.flip();

        LogRecord record = RecordCodec.decode(buf);

        // A wrong offset yields a record that is internally consistent, so the CRC
        // cannot catch it. Comparing the key does.
        if (!java.util.Arrays.equals(record.key(), key)) {
            throw new IOException("key mismatch at offset " + entry.recordPos()
                + ": index and log disagree");
        }
        if (record.type() != RecordType.PUT) {
            throw new IOException("tombstone reachable through the index at offset "
                + entry.recordPos() + ": internal inconsistency");
        }
        return record.value();
    }

    private void readFully(ByteBuffer buf, long position) throws IOException {
        long p = position;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, p);
            if (n < 0) throw new IOException("unexpected end of file at " + p);
            p += n;
        }
    }
```

This replaces the Task 6 stub that threw `UnsupportedOperationException`.

- [ ] **Step 4: Run the tests**

Run: `mvn -q test -Dtest=BitcaskReadTest`
Expected: PASS (except `corruptedByteOnDiskIsDetectedOnRead`, which needs Task 9).
Also run: `mvn -q test -Dtest=BitcaskWriteTest` — all 7 should now pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/onurcanogul/bitcask/Bitcask.java src/test/java/com/onurcanogul/bitcask/BitcaskReadTest.java
git commit -m "feat: read path with crc and key verification"
```

---

## Task 9: Delete (tombstone)

**Files:**
- Modify: `src/main/java/com/onurcanogul/bitcask/Bitcask.java`
- Test: `src/test/java/com/onurcanogul/bitcask/BitcaskDeleteTest.java`

**Interfaces:**
- Consumes: `RecordCodec.encode`, `RecordType.TOMBSTONE`
- Produces: `boolean delete(byte[] key) throws IOException`

- [ ] **Step 1: Write the failing test**

```java
package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BitcaskDeleteTest {

    @TempDir Path dir;

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void deleteRemovesTheKey() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("v"));
            assertTrue(db.delete(b("k")));
            assertNull(db.get(b("k")));
            assertEquals(0, db.size());
        }
    }

    @Test
    void deletingMissingKeyReturnsFalseAndWritesNothing() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            long before = Files.size(dir.resolve("data.log"));
            assertFalse(db.delete(b("nope")));
            assertEquals(before, Files.size(dir.resolve("data.log")));
        }
    }

    @Test
    void deleteAppendsATombstoneToTheLog() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("v"));
            long before = Files.size(dir.resolve("data.log"));
            db.delete(b("k"));
            long after = Files.size(dir.resolve("data.log"));
            assertEquals(before + 27 + 1, after);  // header + 1-byte key, no value
        }
    }

    @Test
    void keyCanBeWrittenAgainAfterDelete() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("v1"));
            db.delete(b("k"));
            db.put(b("k"), b("v2"));
            assertArrayEquals(b("v2"), db.get(b("k")));
        }
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -q test -Dtest=BitcaskDeleteTest`
Expected: FAIL — `delete` does not exist.

- [ ] **Step 3: Implement `delete`**

```java
    public synchronized boolean delete(byte[] key) throws IOException {
        ensureOpen();
        validateKey(key);

        ByteBuffer indexKey = ByteBuffer.wrap(key);
        if (!keyDir.containsKey(indexKey)) {
            return false;   // nothing to cancel; writing a tombstone would be pure garbage
        }

        long seq = nextSeq;
        ByteBuffer record = RecordCodec.encode(seq, System.currentTimeMillis(),
                                               RecordType.TOMBSTONE, key, new byte[0]);
        int size = record.remaining();
        long pos = writePos;

        writeFully(record, pos);
        if (config.syncPolicy() == SyncPolicy.ALWAYS) {
            channel.force(false);
        }

        keyDir.remove(indexKey);
        writePos = pos + size;
        nextSeq = seq + 1;
        return true;
    }
```

- [ ] **Step 4: Run the tests**

Run: `mvn -q test -Dtest=BitcaskDeleteTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/onurcanogul/bitcask/Bitcask.java src/test/java/com/onurcanogul/bitcask/BitcaskDeleteTest.java
git commit -m "feat: delete via tombstone records"
```

---

## Task 10: Recovery (log replay)

**Files:**
- Create: `src/main/java/com/onurcanogul/bitcask/recovery/RecoveryResult.java`
- Create: `src/main/java/com/onurcanogul/bitcask/recovery/Recovery.java`
- Modify: `src/main/java/com/onurcanogul/bitcask/Bitcask.java` (call real recovery in `open`)
- Test: `src/test/java/com/onurcanogul/bitcask/recovery/RecoveryTest.java`

**Interfaces:**
- Consumes: `RecordCodec`, `FileHeader`, `KeyDirEntry`, `RecoveryMode`, `StopReason`, `RecoveryReport`
- Produces:
  - `RecoveryResult(RecoveryReport report, long endOffset, long maxSeq)`
  - `static RecoveryResult replay(FileChannel ch, Map<ByteBuffer,KeyDirEntry> keyDir, RecoveryMode mode) throws IOException`

- [ ] **Step 1: Write the failing test**

```java
package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {

    @TempDir Path dir;

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void dataSurvivesReopen() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("a"), b("1"));
            db.put(b("b"), b("2"));
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertArrayEquals(b("1"), db.get(b("a")));
            assertArrayEquals(b("2"), db.get(b("b")));
            assertEquals(2, db.size());
            assertEquals(2, db.recoveryReport().recordsReplayed());
            assertEquals(StopReason.CLEAN_EOF, db.recoveryReport().reason());
        }
    }

    @Test
    void laterWriteWinsAfterReopen() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("old"));
            db.put(b("k"), b("new"));
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertArrayEquals(b("new"), db.get(b("k")));
            assertEquals(1, db.size());
        }
    }

    @Test
    void deletedKeyStaysDeletedAfterReopen() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("k"), b("v"));
            db.delete(b("k"));
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertNull(db.get(b("k")));
            assertEquals(0, db.size());
        }
    }

    @Test
    void writesContinueAfterReopen() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("a"), b("1"));
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("b"), b("2"));
            assertArrayEquals(b("1"), db.get(b("a")));
            assertArrayEquals(b("2"), db.get(b("b")));
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertEquals(2, db.size());
        }
    }

    @Test
    void truncatedTailIsDiscardedAndReported() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("a"), b("1"));
            db.put(b("b"), b("2"));
        }
        Path data = dir.resolve("data.log");
        long full = Files.size(data);
        try (FileChannel ch = FileChannel.open(data, StandardOpenOption.WRITE)) {
            ch.truncate(full - 3);   // simulate a torn write at the tail
        }

        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertArrayEquals(b("1"), db.get(b("a")));
            assertNull(db.get(b("b")));
            RecoveryReport r = db.recoveryReport();
            assertTrue(r.lostData());
            assertEquals(1, r.recordsReplayed());
        }
    }

    @Test
    void strictModeRefusesToOpenADamagedLog() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("a"), b("1"));
        }
        Path data = dir.resolve("data.log");
        try (FileChannel ch = FileChannel.open(data, StandardOpenOption.WRITE)) {
            ch.truncate(Files.size(data) - 2);
        }
        BitcaskConfig strict = new BitcaskConfig(BitcaskConfig.DEFAULT_MAX_VALUE_SIZE,
                                                 SyncPolicy.NEVER, RecoveryMode.STRICT);
        assertThrows(IOException.class, () -> Bitcask.open(dir, strict));
    }

    @Test
    void tailIsTruncatedSoTheNextWriteLandsAtACleanOffset() throws Exception {
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("a"), b("1"));
        }
        Path data = dir.resolve("data.log");
        try (FileChannel ch = FileChannel.open(data, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5 }));   // garbage tail
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            db.put(b("b"), b("2"));
        }
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertArrayEquals(b("1"), db.get(b("a")));
            assertArrayEquals(b("2"), db.get(b("b")));
            assertEquals(2, db.size());
        }
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -q test -Dtest=RecoveryTest`
Expected: FAIL — reopened engines are empty because `open` still stubs recovery.

- [ ] **Step 3: Implement recovery**

```java
// RecoveryResult.java
package com.onurcanogul.bitcask;

/** Internal: the user-facing report plus the state the engine needs to resume. */
public record RecoveryResult(RecoveryReport report, long endOffset, long maxSeq) {}
```

```java
// Recovery.java
package com.onurcanogul.bitcask;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Map;

/**
 * Rebuilds the KeyDir by replaying the log from start to finish.
 *
 * Validation runs cheapest-first and allocates nothing until every length field
 * has been checked, so a corrupt length can never size an array.
 */
public final class Recovery {

    private Recovery() {}

    public static RecoveryResult replay(FileChannel channel,
                                        Map<ByteBuffer, KeyDirEntry> keyDir,
                                        RecoveryMode mode) throws IOException {
        final long fileSize = channel.size();
        long pos = FileHeader.SIZE;
        long records = 0;
        long maxSeq = 0;
        StopReason reason = StopReason.CLEAN_EOF;
        String detail = null;

        ByteBuffer header = ByteBuffer.allocate(RecordCodec.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);

        while (pos < fileSize) {
            header.clear();
            int read = readAt(channel, header, pos);
            if (read < RecordCodec.HEADER_SIZE) {
                reason = StopReason.SHORT_READ;
                detail = "partial header at " + pos;
                break;
            }
            header.flip();

            byte typeCode = header.get(20);
            int keyLen    = header.getShort(21) & 0xFFFF;
            long valLen   = header.getInt(23) & 0xFFFFFFFFL;

            try {
                RecordCodec.validateHeaderFields(typeCode, keyLen, valLen, fileSize - pos);
            } catch (CorruptRecordException e) {
                reason = StopReason.INVALID_HEADER_FIELD;
                detail = e.getMessage() + " at " + pos;
                break;
            }

            int size = RecordCodec.recordSize(keyLen, (int) valLen);
            ByteBuffer full = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
            if (readAt(channel, full, pos) < size) {
                reason = StopReason.SHORT_READ;
                detail = "partial record at " + pos;
                break;
            }
            full.flip();

            LogRecord record;
            try {
                record = RecordCodec.decode(full);
            } catch (CorruptRecordException e) {
                reason = StopReason.CRC_MISMATCH;
                detail = e.getMessage() + " at " + pos;
                break;
            }

            if (record.seq() <= maxSeq) {
                reason = StopReason.NON_INCREASING_SEQ;
                detail = "seq " + record.seq() + " after " + maxSeq + " at " + pos;
                break;
            }

            ByteBuffer key = ByteBuffer.wrap(Arrays.copyOf(record.key(), record.key().length));
            if (record.type() == RecordType.PUT) {
                keyDir.put(key, new KeyDirEntry(0, pos, size, record.seq()));
            } else {
                keyDir.remove(key);
            }

            maxSeq = record.seq();
            records++;
            pos += size;
        }

        long discarded = fileSize - pos;

        if (discarded > 0 && mode == RecoveryMode.STRICT) {
            throw new IOException("log damaged at offset " + pos + " (" + reason + "): " + detail);
        }
        if (discarded > 0) {
            channel.truncate(pos);
        }

        RecoveryReport report = new RecoveryReport(
            records, keyDir.size(), discarded, discarded > 0 ? pos : -1, reason);

        return new RecoveryResult(report, pos, maxSeq);
    }

    private static int readAt(FileChannel channel, ByteBuffer buf, long position) throws IOException {
        int total = 0;
        long p = position;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, p);
            if (n < 0) break;
            total += n;
            p += n;
        }
        return total;
    }
}
```

Then replace the stub in `Bitcask.open`:

```java
            Map<ByteBuffer, KeyDirEntry> keyDir = new ConcurrentHashMap<>();
            RecoveryResult result = Recovery.replay(channel, keyDir, config.recoveryMode());
            return new Bitcask(config, lock, channel, keyDir,
                               result.report(), result.endOffset(), result.maxSeq() + 1);
```

Change the constructor and the `keyDir` field so the map is passed in rather than created inline.

- [ ] **Step 4: Run every test**

Run: `mvn -q test`
Expected: all pass, including `corruptedByteOnDiskIsDetectedOnRead` from Task 8 — re-enable it now if it was disabled.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/onurcanogul/bitcask src/test/java/com/onurcanogul/bitcask/RecoveryTest.java
git commit -m "feat: log replay recovery with tail truncation and reporting"
```

---

## Task 11: Model-Based Test

**Files:**
- Test: `src/test/java/com/onurcanogul/bitcask/ModelBasedTest.java`

**Interfaces:**
- Consumes: the full `Bitcask` API
- Produces: nothing (test only)

- [ ] **Step 1: Write the test**

```java
package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Applies the same random operation sequence to the engine and to a HashMap,
 * comparing after every step. Reaches states hand-written tests do not.
 */
class ModelBasedTest {

    @TempDir Path dir;

    private static final int OPERATIONS = 5_000;
    private static final int KEY_SPACE = 50;   // small, so overwrites and deletes collide often

    @Test
    void engineMatchesHashMapReference() throws Exception {
        long seed = System.nanoTime();
        Random random = new Random(seed);
        Map<String, byte[]> model = new HashMap<>();

        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            for (int i = 0; i < OPERATIONS; i++) {
                String key = "key:" + random.nextInt(KEY_SPACE);
                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                int roll = random.nextInt(10);

                if (roll < 6) {
                    byte[] value = new byte[random.nextInt(64)];
                    random.nextBytes(value);
                    db.put(keyBytes, value);
                    model.put(key, value);
                } else if (roll < 8) {
                    boolean removedFromDb = db.delete(keyBytes);
                    boolean removedFromModel = model.remove(key) != null;
                    assertEquals(removedFromModel, removedFromDb, "seed=" + seed + " op=" + i);
                } else {
                    assertArrayEquals(model.get(key), db.get(keyBytes), "seed=" + seed + " op=" + i);
                }
            }

            for (int k = 0; k < KEY_SPACE; k++) {
                String key = "key:" + k;
                assertArrayEquals(model.get(key), db.get(key.getBytes(StandardCharsets.UTF_8)),
                                  "seed=" + seed + " final check " + key);
            }
        }

        // The same comparison must hold after a reopen: recovery must rebuild the exact state.
        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            assertEquals(model.size(), db.size(), "seed=" + seed);
            for (Map.Entry<String, byte[]> e : model.entrySet()) {
                assertArrayEquals(e.getValue(), db.get(e.getKey().getBytes(StandardCharsets.UTF_8)),
                                  "seed=" + seed + " after reopen " + e.getKey());
            }
        }
    }
}
```

The seed is printed in every failure message so any failure can be reproduced by hardcoding it.

- [ ] **Step 2: Run it**

Run: `mvn -q test -Dtest=ModelBasedTest`
Expected: PASS. If it fails, hardcode the reported seed and debug that exact sequence.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/onurcanogul/bitcask/ModelBasedTest.java
git commit -m "test: model-based comparison against a hashmap reference"
```

---

## Task 12: Crash Test (`kill -9`)

This is the direct test of the Phase 1 success criterion.

**Files:**
- Test: `src/test/java/com/onurcanogul/bitcask/CrashWriterMain.java`
- Test: `src/test/java/com/onurcanogul/bitcask/CrashTest.java`

**Interfaces:**
- Consumes: the full `Bitcask` API
- Produces: nothing (test only)

- [ ] **Step 1: Write the child-process writer**

```java
package com.onurcanogul.bitcask;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Runs in a separate JVM. Writes keys in order and prints each one to stdout
 * only AFTER put() returns, so the parent knows exactly which writes completed.
 */
public final class CrashWriterMain {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        BitcaskConfig config = BitcaskConfig.defaults();

        Bitcask db = Bitcask.open(dir, config);
        for (int i = 0; ; i++) {
            byte[] key = ("key:" + i).getBytes(StandardCharsets.UTF_8);
            byte[] value = ("value:" + i).getBytes(StandardCharsets.UTF_8);
            db.put(key, value);
            System.out.println(i);
            System.out.flush();
        }
    }
}
```

- [ ] **Step 2: Write the crash test**

```java
package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CrashTest {

    @TempDir Path dir;

    @Test
    void everyAcknowledgedWriteSurvivesKillMinus9() throws Exception {
        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                CrashWriterMain.class.getName(),
                dir.toAbsolutePath().toString())
            .redirectErrorStream(false)
            .start();

        int lastAcknowledged = -1;
        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = out.readLine()) != null) {
                lastAcknowledged = Integer.parseInt(line.trim());
                if (lastAcknowledged >= 2_000) {
                    child.destroyForcibly();     // SIGKILL — no cleanup, no shutdown hook
                    break;
                }
            }
        }
        child.waitFor(10, TimeUnit.SECONDS);
        assertTrue(lastAcknowledged >= 2_000, "child did not write enough before the kill");

        // Release the child's lock file if the OS has not already.
        assertTrue(Files.exists(dir.resolve("data.log")));

        try (Bitcask db = Bitcask.open(dir, BitcaskConfig.defaults())) {
            RecoveryReport report = db.recoveryReport();

            // Every write whose put() returned must still be there.
            for (int i = 0; i <= lastAcknowledged; i++) {
                byte[] value = db.get(("key:" + i).getBytes(StandardCharsets.UTF_8));
                assertNotNull(value, "lost acknowledged write " + i
                    + " (report=" + report + ")");
                assertArrayEquals(("value:" + i).getBytes(StandardCharsets.UTF_8), value);
            }

            // A torn tail is acceptable; silent corruption is not.
            assertTrue(report.bytesDiscarded() >= 0);

            // The engine must be usable afterwards.
            db.put("after-crash".getBytes(StandardCharsets.UTF_8),
                   "ok".getBytes(StandardCharsets.UTF_8));
            assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8),
                              db.get("after-crash".getBytes(StandardCharsets.UTF_8)));
        }
    }
}
```

Note: with `SyncPolicy.NEVER` this test passes because `kill -9` leaves the OS page cache intact — exactly the distinction drawn in the spec (§6.3).

- [ ] **Step 3: Run it**

Run: `mvn -q test -Dtest=CrashTest`
Expected: PASS. It takes a few seconds because it spawns a JVM.

- [ ] **Step 4: Run the whole suite**

Run: `mvn -q test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/onurcanogul/bitcask/CrashWriterMain.java src/test/java/com/onurcanogul/bitcask/CrashTest.java
git commit -m "test: kill -9 crash test proving acknowledged writes survive"
```

---

## Task 13: README

**Files:**
- Create: `README.md`

- [ ] **Step 1: Write the README**

Cover: what the engine is, the Bitcask model in three sentences, the on-disk
format table (copied from the spec §4.2), the API, the configuration table, the
known limits table (spec §13), and how to run the tests. Link to the spec and
this plan.

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: readme covering format, api and limits"
```

---

## Spec Coverage Check

| Spec section | Covered by |
|---|---|
| §3.1 API surface | Tasks 6–9 |
| §3.1.1 Configuration | Task 2 |
| §3.2 Ownership and copying | Task 7 (key copy), Task 8 (fresh array on read) |
| §3.3 Limits | Tasks 2, 3, 7 |
| §3.4 Threading / single writer | Task 5 (lock), Task 7 (`synchronized put`) |
| §4.1 File header | Task 4 |
| §4.2–4.4 Record format, CRC, unsigned reads | Task 3 |
| §5 KeyDir | Tasks 2 (entry), 7 (key copy + wrap), 10 (rebuild) |
| §6 Write path | Task 7 |
| §6.3 fsync policy | Task 7 (`SyncPolicy.ALWAYS` branch) |
| §7 Read path | Task 8 |
| §8 Delete | Task 9 |
| §9 Recovery + validation chain + corruption policy | Task 10 |
| §9.4 Recovery report | Tasks 6 (type), 10 (populated) |
| §10 Lifecycle | Tasks 5, 6 |
| §11 Test strategy, layers 1–3 | Tasks 3–10 (unit), 11 (model), 12 (crash) |
| §12 Deferred work | Not implemented by design |
| §13 Known limits | Task 13 (README) |
