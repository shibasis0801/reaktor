# reaktor-flexbuffer

> **Stability: Experimental** — the exact-layout generated tier is performance- and correctness-tested, but schema fingerprinting, complete upstream reader parity, and a versioned production FFI protocol remain open.

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

See [PERFORMANCE_AUDIT.md](PERFORMANCE_AUDIT.md) for the 2026-07-11 source,
compatibility, use-case, harness, and measured optimization audit.

The audit contains the current JVM, production Node/V8, Android/ART emulator,
release Kotlin/Native, Kotlin-vs-C++, and FlatBuffers-vs-FlexBuffers matrices.
The older reaktorWeb page is historical until it is regenerated from that audit.
Headline characteristics:

- No intermediate navigation-container allocation on generated positional decode; returned bytes, result objects, strings, and materialized collections still allocate.
- Sign-extended minimal-width integers and shared key vectors shrink wire size below the C++ default builder output for repeated-map payloads.
- Generated JVM round trips beat raw Flex, JSON, and ProtoBuf on all measured headline and 26-corpus cases; caller-owned buffers reduce copying further.
- Kotlin is within 1.0-1.1x of optimized C++ Flex on the large numeric and wide-map access paths and beats the C++ builder on the tested unique-string encode cases; C++ remains faster on tiny and string-heavy reads.
- On Node/V8, generated decode wins every measured case; native JSON encode remains faster for string/object-heavy payloads, while generated Flex wins numeric TimeSeries encode.
- On the API 36 ART steady-state matrix, generated roundtrip beats raw Flex and
  JSON in all four cases; physical-device release and Android primitive-kernel
  policy still require a dedicated device A/B.

## Compatibility note (2026-06)

The encoder now writes negative integers at minimal signed widths (C++-compatible). Buffers written by **older** reaktor-flexbuffer versions decode correctly with this reader; buffers written by **this** version require this reader (old readers zero-extended `T_INT` and would mis-read narrow negatives). For rolling upgrades, deploy readers first and writers second. Existing wide-negative buffers do not require rewriting; optional compaction/re-encoding should happen only after old readers are retired.

## Dependencies

- `reaktor-core`
- `reaktor-compiler` (KSP code generation)
- `com.google.flatbuffers:flatbuffers-java` (Android, FFI test surface only)
