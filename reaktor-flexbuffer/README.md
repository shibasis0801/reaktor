# reaktor-flexbuffer

> **Stability: Experimental** — the October review reproduces four open correctness defects (map hashing, empty-map validation, null/default semantics, required-field/layout rejection). Schema fingerprinting, complete upstream reader parity and a production FFI protocol remain open.

`reaktor-flexbuffer` is a Kotlin Multiplatform implementation of Google's FlexBuffers format, engineered for low-latency generated coders, direct memory access, compile-time encode geometry, and C++-compatible sign-extended minimal-width integers.

## Platforms

Android, iOS (Darwin), JVM, JavaScript/Web

## Architecture

### Memory layer (`ld*`/`st*` in `flatbuffers/ByteArray.kt`)

Platform-specialized unchecked little-endian loads/stores:

- **JVM / Android** — `sun.misc.Unsafe` single unaligned loads (HotSpot/ART intrinsics), no bounds checks; graceful fallback when unavailable.
- **iOS / Native** — `getIntAt`/`getLongAt` unaligned-load intrinsics.
- **JS** — integer composition without `Long` emulation for widths ≤ 4; floats through a shared `Float64Array` conversion view.

Width dispatch is a two-comparison binary tree (the C++ `ReadSizedScalar` shape), with separate sign-extended (`T_INT`) and zero-extended (`T_UINT`/offset) paths.

### Reader (`flatbuffers/FlexBuffers.kt`)

`Reference`/`Map`/`Vector` operate directly on `ByteArray` — no buffer interface, no virtual dispatch on reads. Maps precompute their packed-type-array base; typed vectors fold into `Vector` via a final field instead of subclass overrides. Bulk reads (`toIntArray` etc.) are width-specialized constant-stride loops.

### Writer (`flatbuffers/FlexBuffersBuilder.kt`)

Direct `ByteArray` writes (no interface), single 8-byte-store padding, width-specialized bulk array writers, and the **key-block fast path**: a generated coder registers its pre-encoded sorted key set once per buffer; every map of that type shares both the key bytes and a single key vector. Repeated maps cost zero key writes, zero sorting, zero key-width computation — beyond what the C++ builder does.

Integers store at C++-compatible sign-extended minimal widths (`-5` is one byte), fixing both wire size and cross-language interop for negative values.

### Generated coders (KSP, `@Struct`)

`FlexCoderProcessor` emits per class:

- `encodeKeyed(builder, value, keyOffset)` — all field keys resolve through the shared key block; nested coders chain by direct object call.
- `decodeAt(buf, end, byteWidth)` — positional decode through `FlexRead` statics with hoisted locals; no navigation `Map`/`Vector` allocations (result objects, strings, and materialized collections still allocate).
- `@JvmInline` value-class Accessor — zero-copy field reads over the buffer, lazy collection views.

Property-level `@SerialName` values are the wire keys and control unsigned-UTF-8
map order; Kotlin source identifiers remain independent. Duplicate wire names
fail generation rather than producing an ambiguous positional layout.

### Five access tiers

1. **Accessor** (zero-copy, lazy) — `bytes.asUserProfile().username`
2. **FlexCoder** (generated) — `FlexBuffers.decode<UserProfile>(bytes)`
3. **Accelerated serializer** — `FlexBuffers.decode(serializer<T>(), bytes)` routed to the coder
4. **Raw kotlinx.serialization** — `FlexDecoderV2`/`FlexEncoderV2` fallback for non-`@Struct` types
5. **JSON baseline** — interop/debug

### Generated-coder registration

Direct coder calls do not require global registration:

```kotlin
val bytes = FlexBuffers.encode(UserProfileFlexCoder, value)
val decoded = FlexBuffers.decode(UserProfileFlexCoder, bytes)
```

Register a coder when using the reified or serializer API. Every generated coder
has an idempotent `register()` method:

```kotlin
UserProfileFlexCoder.register()
val bytes = FlexBuffers.encode(value)
```

A module can configure one aggregate registrar through the KSP options
`reaktor.flexcoder.registrar.package` and
`reaktor.flexcoder.registrar.object`, then call its `register()` once at startup.
The old generated top-level `registerGeneratedFlexCoders()` facade has been
removed because incremental KSP rounds could silently generate an incomplete
facade.

Registration is idempotent for the same coder. A different coder attempting to
claim an existing serializer serial name fails immediately; registration and
registry clearing belong in single-threaded startup, before concurrent reads.

For a copy-free handoff, use a caller-owned `FlexBuffersBuilder` with
`encodeToBuffer`; the returned view remains valid only until that builder is
cleared or reused.

## Native C++ layer

- `cpp/darwin` + `cpp/droid` — a minimal FFI handshake (`Reaktor_FlexHelloBytes`) bridged via cinterop (iOS) and JNI (Android), decoded by the Kotlin reader and Maestro-verified on device. JVM/JS return empty stubs.
- `cpp/bench` — standalone reference harnesses (not linked into apps) used as the performance oracle the Kotlin implementation is measured against, including the adversarial Kotlin-vs-C++ ledger run by `AdversarialPerformanceHarnessTest`.

## Performance

The current ledger is [PERFORMANCE_REVIEW_2026-10-03.md](PERFORMANCE_REVIEW_2026-10-03.md):
two-fork JVM JMH on M4 Pro, actual Chrome, physical S23 Ultra and physical iPhone
14 Pro, JSON/kotlinx ProtoBuf/packed Google Java Protobuf, allocations, wire sizes,
partial reads, validation and reproduced stability failures.

- Generated JVM encode/decode costs are 0.487/0.276 µs (UserProfile), 3.476/1.715 µs
  (ApiResponse) and 0.376/0.165 µs (TimeSeries). Full decode still allocates models.
- Browser object encode often loses to JSON; generated decode is not a universal win.
- Caller-owned buffers avoid the result copy; they do not always reduce runtime and
  the view expires on builder clear/reuse.
- Physical Android public-ByteBuffer A/B shows large vector opportunities. All current
  Android primitive bulk actuals return false: the kernels are not shipped.
- Fix the four repros and lifetime/schema/cache issues before broad production use.
  Codec results do not measure FFI hop latency or a mutable-memory engine.

[PERFORMANCE_AUDIT.md](PERFORMANCE_AUDIT.md) and
[PERFORMANCE_VERIFICATION_2026-07-13.md](PERFORMANCE_VERIFICATION_2026-07-13.md)
retain the historical July campaigns. Their schedules and fixtures differ from
October; they are not a paired comparison.

The published [implementation guide](https://reaktor.build/docs/flexbuffer-implementation),
[current review](https://reaktor.build/docs/flexbuffer-performance-review) and
[complete From Bytes to Mutable Memory book](https://reaktor.build/docs/flatbuffers-flexbuffers-mutable-memory)
are grouped under **FlexBuffers and FFI → FlexBuffers**. The book's mutable engine
is a proposed separate runtime, not implemented Reaktor behavior.

## Compatibility note (2026-06)

The encoder now writes negative integers at minimal signed widths (C++-compatible). Buffers written by **older** reaktor-flexbuffer versions decode correctly with this reader; buffers written by **this** version require this reader (old readers zero-extended `T_INT` and would mis-read narrow negatives). For rolling upgrades, deploy readers first and writers second. Existing wide-negative buffers do not require rewriting; optional compaction/re-encoding should happen only after old readers are retired.

## Dependencies

- `reaktor-core`
- `reaktor-compiler` (KSP code generation)
- `com.google.flatbuffers:flatbuffers-java` (Android, FFI test surface only)
