# reaktor-flexbuffer runtime-performance audit

Date: 2026-07-11/12

## Executive conclusion

The generated `FlexCoder` tier is the fastest general-purpose serialization path
in this module. It wins every one of the 26 JVM corpus models against raw
serializer-driven FlexBuffers, kotlinx JSON, and kotlinx ProtoBuf in both encode
and decode. The caller-owned builder path is faster again when the consumer can
avoid the final result copy.

The optimized generated serializer-routed round trip improved under the exact
original JMH schedule as follows:

| Case | Original | Final | Runtime change | Original allocation | Final allocation | Allocation change |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| UserProfile | 0.936 us | 0.808 us | -13.7% | 3,440 B/op | 3,256 B/op | -5.3% |
| ApiResponse | 6.337 us | 5.227 us | -17.5% | 24,600 B/op | 23,624 B/op | -4.0% |
| TimeSeries | 2.298 us | 0.527 us | -77.1% | 19,032 B/op | 8,648 B/op | -54.6% |

Configuration: JMH 1.37, Temurin JDK 25.0.2, three forks, five 750 ms
warmups, seven 750 ms measurements, one thread, fixed 1 GiB heap, GC profiler.
The paired reports are:

- `build/reports/benchmarks/reaktor-flexbuffer-jmh-baseline.json`
- `build/reports/benchmarks/full-2026-07-11/jvm-flex-full-final-exact.json`

The important qualification is semantic: generated positional coders are an
**exact-layout, trusted-buffer performance tier**. They are appropriate for
version-locked, jointly deployed payloads. They are not yet a schema-evolution
mechanism for persistence or independently deployed services; those use cases
still require a generated fingerprint/keyed fallback gate.

## What was measured

The campaign separated work that older harnesses had mixed together:

- direct generated coder encode, decode, and full round trip;
- serializer API routed to a registered generated coder;
- raw serializer-driven FlexBuffers with an explicitly empty coder registry;
- kotlinx JSON and kotlinx ProtoBuf, encode/decode/full;
- caller-owned copy-free buffers;
- generated and generic accessor reads;
- a 26-model directional JVM corpus;
- primitive vector encode/materialize at 256 and 4,096 elements;
- re-encoding original versus generated-decoded materialized models;
- production Kotlin/JS on Node/V8;
- release Kotlin/Native in an iOS simulator;
- byte-identical Kotlin FlexBuffers versus C++ FlexBuffers;
- 13 semantic-equivalent C++ fixtures comparing FlexBuffers with FlatBuffers.

Every setup verifies equality or a semantic checksum before timing. Raw Flex
runs assert that the generated-coder registry remains empty, so they cannot
silently route into the generated fast path. The corrected full-roundtrip
benchmarks return the decoded value to the JMH blackhole; they do not include a
second traversal that checksums every collection inside the timed region.

## Final JVM headline matrix

Three forks, three 400 ms warmups, five 400 ms measurements, JDK 25, GC
profiler. Values are `us/op; B/op`.

| Case | Direct generated | Caller-owned | Serializer-routed | Raw Flex | JSON | ProtoBuf |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| UserProfile | 0.824; 3,256 | **0.795; 2,408** | 0.832; 3,264 | 1.699; 4,328 | 1.740; 4,576 | 2.127; 12,744 |
| ApiResponse | 5.218; 23,624 | **5.093; 16,496** | 5.263; 23,624 | 15.371; 29,856 | 21.071; 64,280 | 15.317; 101,112 |
| TimeSeries | 0.534; 8,648 | **0.451; 4,288** | 0.533; 8,648 | 5.158; 26,384 | 43.007; 103,825 | 13.307; 43,496 |

Across these three fixtures, geometric-mean slowdown versus direct generated is:

| Path | Runtime ratio | Allocation geomean |
| --- | ---: | ---: |
| Caller-owned | **0.926x** | 5,543 B/op |
| Direct generated | 1.000x | 8,729 B/op |
| Serializer-routed generated | 1.005x | 8,737 B/op |
| Raw serializer Flex | 3.884x | 15,051 B/op |
| ProtoBuf | 5.734x | 38,270 B/op |
| JSON | 8.818x | 31,258 B/op |

Serializer routing is essentially free after registration. Caller ownership is
most valuable on primitive payloads: it removes the final output copy and cuts
TimeSeries round-trip allocation from 8,648 to 4,288 B/op.

Machine-readable result:
`build/reports/benchmarks/full-2026-07-11/jvm-headline-final-3fork.json`.

## 26-model JVM corpus

The corpus covers nested objects, lists, maps, chat, configuration, telemetry,
notifications, commerce, media, search, fitness, finance, graphs, games, IoT,
education, logistics, market data, and social graph deltas.

| Direction | Competitor / generated geomean | Generated wins |
| --- | ---: | ---: |
| Encode | Raw Flex 2.151x | 26/26 |
| Encode | JSON 2.128x | 26/26 |
| Encode | ProtoBuf 2.088x | 26/26 |
| Decode | Raw Flex 4.245x | 26/26 |
| Decode | JSON 9.199x | 26/26 |
| Decode | ProtoBuf 6.558x | 26/26 |

The corpus baseline/final schedules are intentionally short (one fork, two
warmups, three 200 ms measurements). Their broad ranking is reliable; movement
of roughly one percent between baseline and final is noise. Do not use those
short runs to claim small regressions or improvements.

Results:

- `build/reports/benchmarks/full-2026-07-11/jvm-corpus-baseline.json`
- `build/reports/benchmarks/full-2026-07-11/jvm-corpus-final.json`

## Primitive-vector optimization

Natural-width primitive vectors now use platform bulk transfer where it proved
faster. Compact vectors retain scalar conversion when element width differs.
The JVM policy is evidence-based rather than uniform: bulk stores are retained
for Short, Int, Float, and Double; Long stores and integer materialization stay
scalar where HotSpot generated faster code.

At 4,096 elements, representative encode results are:

| Vector | Baseline | Final | Speedup |
| --- | ---: | ---: | ---: |
| Natural Short | 1.688 us | 0.132 us | **12.77x** |
| Natural Int | 2.247 us | 0.241 us | **9.33x** |
| Natural Long | 0.308 us | 0.306 us | 1.01x |
| Natural Float | 0.592 us | 0.281 us | **2.11x** |
| Natural Double | 0.604 us | 0.415 us | **1.46x** |
| Compact Short | 1.462 us | 0.132 us | **11.08x** |
| Compact Float | 0.602 us | 0.254 us | **2.37x** |
| Compact Double | 0.602 us | 0.262 us | **2.30x** |

Across all 20 encode cells the point-estimate geometric speedup is 2.51x, with
18/20 wins. Decode/materialize improved by 1.07x geometrically; its allocation
is already the returned primitive arrays and therefore close to the semantic
floor. Small primitive deltas need a matched three-fork rerun; the multi-fold
encode gains are large enough to be unambiguous.

The implementation includes JVM `Unsafe.copyMemory`, Native pinned `memcpy`,
and JavaScript typed-array transfer. Android deliberately keeps scalar transfer
until it is measured on ART rather than inferred from HotSpot.

## Production Node/V8 matrix

Kotlin/JS was built as a production library and executed on Node 22.18.0 / V8
12.4. Each cell used one 2,000-iteration warmup and seven measured batches of
5,000 operations. Values are median `us/op`.

| Case / direction | Generated | Raw Flex | JSON | ProtoBuf |
| --- | ---: | ---: | ---: | ---: |
| User encode | 14.944 | 19.332 | **4.905** | 20.585 |
| User decode | **2.316** | 5.105 | 7.501 | 11.432 |
| API encode | 82.516 | 134.211 | **51.164** | 119.251 |
| API decode | **10.570** | 38.491 | 55.950 | 85.724 |
| TimeSeries encode | **17.008** | 20.479 | 32.583 | 191.388 |
| TimeSeries decode | **4.028** | 32.877 | 91.812 | 122.302 |

Generated decoding wins every Node cell, from 3.24x versus JSON on UserProfile
to 30.4x versus ProtoBuf on TimeSeries. Generated encoding beats raw Flex and
ProtoBuf everywhere, and beats JSON on numeric TimeSeries, but V8's native JSON
string builder remains faster for string/object-heavy User and API payloads.
That is a target-specific remaining hotspot, not a reason to blend encode and
decode into one flattering number.

The final run follows an additional JS hot-path fix: primitive-array range
checks now use overflow-safe `Int` capacity arithmetic rather than `Long` byte
multiplication. That removes accidental BigInt from every 16/32-bit typed-array
transfer. Under the same Node schedule, TimeSeries generated encode moved from
18.362 to 17.008 us/op (-7.4%) and raw Flex encode from 23.211 to 20.479 us/op
(-11.8%); smaller cross-run movements should still be treated as noise.

The JS fixture explicitly normalizes `Float` arithmetic through binary32.
Kotlin/JS otherwise keeps intermediate `Float` arithmetic as a JavaScript
Number, while FlexBuffers and ProtoBuf correctly round it to IEEE-754 float32.

Wire sizes (`generated / raw Flex / JSON UTF-8 / ProtoBuf`) are:

- User: `833 / 833 / 710 / 473`
- API: `7111 / 7489 / 8770 / 5822`
- TimeSeries: `4340 / 4340 / 5807 / 4157`

## Android/ART matrix

The common instrumentation suite ran on the available arm64 API 36 Android
emulator: 64/64 tests passed. The scored benchmark uses an explicit unmeasured
10,000-iteration prime for every implementation/direction, then 500 warmups and
three 5,000-operation runs (minimum reported). This matters on ART: compilation
is installed in the background, so the old fully inlined harness reported the
first User encode while it was still interpreted.

Values are steady-state `us/op` on a debug instrumentation APK:

| Case | Generated encode | Generated decode | Generated RT | Serializer-routed RT | Raw Flex RT | JSON RT |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| UserProfile | 8.44 | 5.75 | **14.19** | 13.03 | 20.56 | 22.01 |
| ChatThread | 24.24 | 8.76 | **33.00** | 32.43 | 65.20 | 117.89 |
| ApiResponse | 62.47 | 27.04 | **89.51** | 86.87 | 158.54 | 255.64 |
| TimeSeries | 10.92 | 6.79 | **17.71** | 16.65 | 51.55 | 349.86 |

Generated round trip beats both raw Flex and JSON in all four cases. As on V8,
JSON encode alone remains faster for string/object-heavy User and API data;
generated decode more than recovers that difference. Direct versus
serializer-routed deltas are benchmark noise at this resolution.

These are useful ART/code-generation results, not physical-device release
numbers: the emulator does not measure phone thermals, OEM runtimes, or a
minified release APK. It therefore does not justify enabling the currently
scalar Android primitive bulk kernels without a physical-device A/B.

## Release Kotlin/Native matrix

The release AOT `iosSimulatorArm64` executable ran eight batches of 100,000
operations per cell on an iOS 26.5 simulator. The table is the median of rounds
2-8; round 1 is discarded. A simulator measures the Native code generator and
allocator on the host CPU, not physical-device thermals.

| Case | Generated encode | Generated decode |
| --- | ---: | ---: |
| UserProfile | 2.493 us | 1.165 us |
| ApiResponse | 22.129 us | 11.400 us |
| ChatThread | 8.225 us | 3.783 us |
| TimeSeries | 3.331 us | **0.276 us** |

Native primitive writes use a single pinned `memcpy` for compatible vector
widths. The extremely small TimeSeries decode is expected: the generated path
bulk-copies its two primitive vectors into the returned arrays.

## Kotlin FlexBuffers versus C++ FlexBuffers

`AdversarialPerformanceHarnessTest.flexKotlinVsFlexCppAdversarialHarness`
compiles the vendored C++ reference with `clang++ -O3 -DNDEBUG -mcpu=native`.
Both implementations consume byte-identical Kotlin-produced fixtures, read the
same fields, use the same checksums, and run 750 warmups, 7,500 iterations, and
five batches (2,000 iterations for the two large string-encode cases).

| Case | Kotlin | C++ | Kotlin/C++ |
| --- | ---: | ---: | ---: |
| Tiny key decode | 0.175 us | 0.071 us | 2.5x |
| Tiny index decode | 0.063 us | 0.011 us | 5.9x |
| Tiny three-key partial read | 0.175 us | 0.030 us | 5.8x |
| 256 sparse misses | 2.945 us | 2.619 us | 1.1x |
| String table scan | 2.668 us | 0.923 us | 2.9x |
| TimeSeries numeric scan | 1.047 us | 1.010 us | 1.0x |
| 64 random wide-map keys | 4.698 us | 4.406 us | 1.1x |
| 64 sequential indexes | 0.133 us | 0.119 us | 1.1x |
| Unique-string encode, sharing | **64.117 us** | 126.929 us | 0.5x |
| Unique-string encode, no sharing | **47.417 us** | 57.810 us | 0.8x |

Kotlin loses 8/10 access comparisons, but the large numeric/wide-map paths are
within 1.0-1.1x of C++, and Kotlin's builder wins both unique-string encode
cases. Tiny reads retain unavoidable JVM/JIT and wrapper costs. This normalized
ledger supersedes older docs that incorrectly gave the two implementations
different work and reported Kotlin as winning string and sparse reads.

## C++ FlatBuffers versus FlexBuffers

The second C++ harness builds semantically equivalent FlatBuffer and FlexBuffer
representations for 13 fixtures. All 13 partial/full checksums match. With
1,000 warmups, 10,000 iterations, and seven runs:

- FlatBuffers faster encode: 13/13, 6.33x geometric mean.
- FlatBuffers faster partial access: 12/13, 1.17x geometric mean.
- FlatBuffers faster full access: 12/13, 1.13x geometric mean.
- FlatBuffers smaller as raw bytes: 8/13.
- FlatBuffers smaller after an 8-byte schema/version id: 8/13.
- FlatBuffers smaller after including the actual schema: 0/13.

This is the expected tradeoff: when both endpoints already share a schema,
generated FlatBuffers is the encode-performance ceiling. FlexBuffers buys
self-description, schema-less inspection, and often smaller schema-inclusive
transport at a modest access cost and a large builder cost in C++.

## Runtime changes retained

### Generated coder geometry

- Generated root encode/decode bypass nullable key resolution and root
  `Reference`/`Map` allocation.
- `decode(bytes, limit)` handles array-backed `ReadBuffer` slices without a
  copy or wrapper navigation.
- Generated maps use one pre-encoded key block and compile-time key offsets.
- Direct scalar reads use exact layout rather than packed-type dispatch.
- Nested vector/map width geometry uses the correct relative element index.
- Public non-reflective `encode(coder, value)` / `decode(coder, bytes)` entry
  points expose the fastest path without reified registry lookup.

### Registration correctness

- Every generated coder now has an idempotent `register()` method.
- A configured module registrar object is emitted once at KSP `finish()` from
  the complete discovered set, deterministically sorted.
- The registrar is aggregating and records all originating files, preventing an
  incremental KSP round from silently truncating the registry to dirty symbols.
- Registry lookup honors class-level `@SerialName` for the serializer API.
- Property-level `@SerialName` now drives unsigned-UTF-8 key ordering, key-block
  bytes, offsets, encode, and positional decode while Kotlin source identifiers
  remain independent.
- Duplicate property wire names fail generation, and two different coders can no
  longer silently replace one another under the same serializer serial name.

The old generated top-level `registerGeneratedFlexCoders()` facade was removed;
call the configured module object (for this module,
`ReaktorFlexbufferCoders.register()`) or an individual
`SomeTypeFlexCoder.register()`.

### Primitive collections and memory transfer

- Generated primitive lists materialize into primitive arrays behind structural
  read-only `List` wrappers, avoiding boxed `ArrayList` storage.
- `ShortArray` and unsigned primitive arrays have direct typed-vector writers.
- Width scans terminate at their maximum possible width and include the vector
  size prefix in width selection.
- Compatible primitive arrays use platform bulk transfer; policies that
  regressed HotSpot were measured and reverted.
- Common bulk bounds use overflow-safe `Int` capacity division, avoiding both
  integer overflow and Kotlin/JS BigInt on 16/32-bit vector paths.
- Raw float/double bits, NaNs, infinities, signed zero, empty vectors, compact
  widths, and keyed vectors have cross-platform tests.

### Strings and keys

- JVM/Android compact Latin-1 length scanning checks eight backing bytes at a
  time; a three-fork JVM A/B measured User encode 5.8% faster and API neutral.
  A matched, fully hot API 36 ART diagnostic retained the scanner at 7.83 us
  versus 15.21 us for the portable length loop on the full caller-owned User
  encode. Cold/interpreted ART samples were discarded.
- JS uses `TextEncoder.encodeInto` and `TextDecoder`.
- Native keeps a direct ASCII path and Foundation fallback.
- Key sort/search order is unsigned UTF-8 byte order, matching C/C++ `strcmp`.
- Non-ASCII map lookup encodes a query once per binary search.

### Pools and validation assumptions

- Native's single-slot encoder/decoder pool claims instances with CAS; the old
  volatile check-then-clear could lend one mutable object to two threads.
- The 16-slot builder pool remains: replacing it with JVM ThreadLocal pooling
  was 2.6-5% slower and was reverted.
- `Blob.get(size)` is rejected, invalid numeric strings return the documented
  zero value, and signed typed integers extend correctly into unsigned arrays.
- Public `ByteArray.toFlexMap()` validates the packed root type before treating
  the root value as a relative map offset, while generated exact-layout decoders
  retain their unchecked trusted-buffer entry point.
- Cross-platform benchmark timing now has a real call boundary and a full
  unmeasured prime, preventing ART's background JIT from making case order look
  like a codec regression.

## Rejected experiments

- JVM/Android per-thread builder pooling: 2.6-5% slower; reverted.
- Special-casing materialized primitive `List` wrappers during re-encode:
  ordinary encode regressed 1.1-3.6% and decoded-model encode regressed
  1.3-1.9%; reverted.
- Bulk JVM integer materialization and Long stores: C2's scalar loop was faster;
  those operations remain scalar.

Rejected code is not left on the production path. The re-encode benchmark is
kept as a regression detector.

## Actual use cases and policy

| Tier/use case | Source status and policy |
| --- | --- |
| Generated model encode/decode | Implemented and heavily tested. Current discovered consumers are module fixtures, tests, and profilers; no production model call site was found. |
| Generated accessors | Best for partial reads; current consumers are tests/benchmarks. Hoist collection views rather than rebuilding them per element. |
| Serializer API | Routes to a registered coder at effectively zero extra runtime cost; otherwise uses the raw keyed fallback. |
| Raw serializer Flex | Compatibility/keyed fallback for non-`@Struct` shapes, not the peak-performance tier. |
| Caller-owned buffers | Fastest full round trip and lowest allocation; no downstream network/storage consumer currently uses it. |
| FFI | The real path is a native hello/vector proof plus BestBuds dev verification. The typed RPC protocol remains experimental and unversioned. |
| Persistence/ObjectStore | No schema-evolution integration or long-lived migration test exists yet. Do not make positional decode the persistence default. |

Recommended operational policy:

1. Use generated exact-layout coders for trusted, ephemeral, jointly deployed
   payloads.
2. Use caller-owned builders when the next layer can consume the buffer before
   the builder is reused.
3. Use accessors for narrow reads; do not materialize data the caller will not
   consume.
4. Validate once at untrusted ingress, then use the trusted internal path.
5. Keep long-lived or independently deployed contracts on a compatible keyed
   path until schema fingerprint fallback is implemented.
6. If a schema is already mandatory and shared, benchmark generated FlatBuffers;
   FlexBuffers is not expected to win schema-known encode.

## Compatibility and safety blockers

### Exact-layout fingerprint

Generate a fingerprint from serialized field names, types, nullability, nesting,
and relevant defaults. Positional decode/access should run only after a match;
otherwise fall back to keyed decode. Expose the distinction as an API concept,
for example `ExactLayout` versus `CompatibleKeyed`.

KSP also needs complete serialization semantics for `@Transient`, defaults,
unsigned types, and unsupported nested
collection/map shapes. It must fail compilation rather than generate a partial
coder.

### Upstream reader parity

- Fixed typed vectors (type ids 16-24) still need complete public read support.
- Deprecated string vectors need upstream null-terminated compatibility.
- Port the remaining upstream fixed-vector, indirect scalar, reuse, mutation,
  verifier, Unicode, and golden-binary tests.
- Direct JVM/Android loads intentionally skip bounds checks. Untrusted data must
  enter through an explicit validated boundary.

### Wire upgrade order

The encoder writes negative integers at C++-compatible minimal signed widths.
New readers decode old wide-negative buffers. Old Reaktor readers can misread
new narrow-negative buffers because they zero-extended `T_INT`. Deploy readers
first, writers second; persisted buffers do not need rewriting unless optional
compaction is desired after old readers are gone.

## Remaining performance roadmap

Ordered by expected return:

1. Split JS 32-bit offsets/metadata from true 64-bit values. Kotlin/JS `Long`
   becomes BigInt/emulation work on the builder path and is now the largest
   obvious target-specific gap.
2. Generate accessor primitive traversal (`fieldSize`, `fieldAt`) that does not
   reconstruct a `Vector`/wrapper on repeated element access.
3. Pin once per generated Native decode and use a raw-pointer cursor for
   string/map-heavy payloads; bulk primitive decode is already near its floor.
4. Add schema fingerprints and keyed fallback so the fast tier can safely reach
   persistence and cross-service use cases.
5. Benchmark repeated-value reuse and key-sharing policy per workload; do not
   enable upstream heuristics globally without A/B evidence.
6. Tier retained builder capacity and clear pooled decoder roots so one giant
   payload cannot remain retained indefinitely.
7. Move benchmark models and runners out of production `commonMain`, and narrow
   the broad `api(:reaktor-core)` dependency. The JMH fat jar is roughly 61 MiB
   because it pulls unrelated Spring, Netty, crypto, and database stacks.
8. Run the primitive bulk policy and release matrix on physical Android/ART
   hardware. The API 36 emulator establishes ART behavior but cannot establish
   physical-device thermals or OEM/runtime performance.

## Reproduction

```bash
# Build the JMH jar
./gradlew :reaktor-flexbuffer:jvmBenchmarkBenchmarkJar

# Full JVM benchmark task (configured 3-fork JMH)
./gradlew :reaktor-flexbuffer:jvmBenchmarkAllocationStats

# Correctness
./gradlew :reaktor-compiler:test
./gradlew :reaktor-flexbuffer:jvmTest
./gradlew :reaktor-flexbuffer:jsNodeTest
./gradlew :reaktor-flexbuffer:iosSimulatorArm64Test
./gradlew :reaktor-flexbuffer:testDebugUnitTest

# Android/ART (connected emulator/device; the benchmark self-primes before scoring)
./gradlew :reaktor-flexbuffer:connectedDebugAndroidTest

# Production Node runner
./gradlew :reaktor-flexbuffer:compileProductionLibraryKotlinJs
REAKTOR_JS_BENCH_WARMUP_ITERATIONS=2000 \
REAKTOR_JS_BENCH_ITERATIONS=5000 \
REAKTOR_JS_BENCH_WARMUP_ROUNDS=1 \
REAKTOR_JS_BENCH_ROUNDS=7 \
node --input-type=module -e \
  "import('./reaktor-flexbuffer/build/compileSync/js/main/productionLibrary/kotlin/reaktor-reaktor-flexbuffer.mjs').then(m => m.runJsFlameChart())"

# C++ verification and scored run
clang++ -O3 -DNDEBUG -mcpu=native -std=c++17 \
  -I .github_modules/flatbuffers/include \
  reaktor-flexbuffer/cpp/bench/flatbuffers_vs_flexbuffers.cpp \
  -o reaktor-flexbuffer/build/tmp/flatbuffers_vs_flexbuffers
reaktor-flexbuffer/build/tmp/flatbuffers_vs_flexbuffers --verify-only
reaktor-flexbuffer/build/tmp/flatbuffers_vs_flexbuffers \
  --warmup 1000 --iters 10000 --runs 7
```

Run scored suites sequentially on a cool machine. Concurrent benchmark forks,
Gradle compilers, simulators, or linkers cause visible multi-fold swings.

## Validation status

- JVM generated, fallback, hardening, registration, upstream interop, and C++
  adversarial tests: passed.
- Production Node setup guards and 24-cell matrix: passed.
- iOS release executable build and eight direct-coder cells: passed.
- iOS simulator tests, Android compilation/unit tests, and the final full
  cross-target regression task: passed.
- API 36 ART instrumentation: 64/64 tests passed; the corrected steady-state
  generated/raw/JSON matrix passed equality guards and completed.
- C++ Kotlin-vs-C++ byte/checksum verification: passed.
- C++ FlatBuffers-vs-FlexBuffers verification: 13/13 passed.
- `git diff --check`: passed at handoff.

The published reaktorWeb FlexBuffer page contains older non-normalized benchmark
claims and should be treated as historical until regenerated from this audit.
