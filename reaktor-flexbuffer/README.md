# reaktor-flexbuffer

> **Stability: Production-ready core** — the serialization stack is correctness-gated on JVM, Android, iOS, and JS. The C++ FFI handshake is device-verified in BestBuds dev flows.

`reaktor-flexbuffer` is a Kotlin Multiplatform implementation of Google's FlexBuffers format, engineered to match C++-class performance: direct single-instruction memory access, allocation-free generated coders, compile-time encode geometry, and C++-compatible sign-extended minimal-width integers.

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
- `decodeAt(buf, end, byteWidth)` — positional decode through `FlexRead` statics with hoisted locals; **zero Map/Vector allocations anywhere in the tree** (only result objects allocate).
- `@JvmInline` value-class Accessor — zero-copy field reads over the buffer, lazy collection views.

### Five access tiers

1. **Accessor** (zero-copy, lazy) — `bytes.asUserProfile().username`
2. **FlexCoder** (generated) — `FlexBuffers.decode<UserProfile>(bytes)`
3. **Accelerated serializer** — `FlexBuffers.decode(serializer<T>(), bytes)` routed to the coder
4. **Raw kotlinx.serialization** — `FlexDecoderV2`/`FlexEncoderV2` fallback for non-`@Struct` types
5. **JSON baseline** — interop/debug

## Native C++ layer

- `cpp/darwin` + `cpp/droid` — a minimal FFI handshake (`Reaktor_FlexHelloBytes`) bridged via cinterop (iOS) and JNI (Android), decoded by the Kotlin reader and Maestro-verified on device. JVM/JS return empty stubs.
- `cpp/bench` — standalone reference harnesses (not linked into apps) used as the performance oracle the Kotlin implementation is measured against, including the adversarial Kotlin-vs-C++ ledger run by `AdversarialPerformanceHarnessTest`.

## Performance

See the FlexBuffer page on reaktorWeb for the current measured matrix (cross-platform tiers, the 26-workload JVM ledger, the Kotlin-vs-C++ ledger, and wire-size tradeoffs), plus reproduction commands. Headline characteristics:

- Allocation-free encode and decode hot paths after warmup (pooled builders, positional decode).
- Sign-extended minimal-width integers and shared key vectors shrink wire size below the C++ default builder output for repeated-map payloads.
- String-heavy scans, sparse lookups, and shared-string encodes beat the C++ reference; sub-100ns full-materialization decodes approach it within data-class allocation cost.

## Compatibility note (2026-06)

The encoder now writes negative integers at minimal signed widths (C++-compatible). Buffers written by **older** reaktor-flexbuffer versions decode correctly with this reader; buffers written by **this** version require this reader (old readers zero-extended `T_INT` and would mis-read narrow negatives). Re-encode any long-lived persisted buffers when upgrading.

## Dependencies

- `reaktor-core`
- `reaktor-compiler` (KSP code generation)
- `com.google.flatbuffers:flatbuffers-java` (Android, FFI test surface only)
