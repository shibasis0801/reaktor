# October performance and stability review

The 3 October 2026 review confirms that generated FlexBuffers are a strong binary
codec for jointly deployed models, especially numeric vectors and partial reads.
It also reproduces four correctness defects that block broad adoption. Browser
object encoding remains a major weakness. Android has large measured bulk-transfer
opportunities that are **not implemented in the production kernels**.

This is the current measurement ledger. The
[implementation guide](https://reaktor.build/docs/flexbuffer-implementation) retains the July campaign
as historical evidence. The
[complete wire-format and mutable-memory book](https://reaktor.build/docs/flatbuffers-flexbuffers-mutable-memory)
supplies design input for a separate mutable engine. Neither codec microbenchmarks
nor the book establish an end-to-end [FFI](https://reaktor.build/docs/reaktor-ffi) latency result.

:::warning[Correctness gate]

The review did not modify production runtime or compiler code. The failures below
remain open: collection hash consistency, valid-empty-map validation, explicit null
versus missing/default semantics, and generated required-field/layout rejection.
Exact-layout access additionally requires a trusted schema contract; structural
validation alone cannot prove it. Do not generalize these passing fixture timings
into a production stability guarantee.

:::

## Evidence and protocol {#protocol}

- **Source:** `b587950324baba646bf8b3341db55611ae2d5d02`. The reviewed
  `reaktor-flexbuffer` and compiler sources were unchanged when documentation was
  refreshed on 4 October.
- **Host:** Apple M4 Pro, 14 cores, 48 GB; Temurin Java 25.0.2.
- **JVM:** JMH 1.37, two fresh forks, three 500 ms warmups, five 500 ms measurements,
  one thread, fixed 1 GiB heap, GC profiler. Tables report means with JMH's 99.9%
  error estimate and allocated bytes per operation. This schedule differs from July;
  the two campaigns are not a paired optimization A/B.
- **Android:** physical Samsung Galaxy S23 Ultra SM-S918B, Snapdragon 8 Gen 2,
  Android 16/API 36, warm ART instrumentation APK. These are not release-APK numbers.
- **iOS:** physical iPhone 14 Pro, A16, iOS 26.7.1, optimized Kotlin/Native release
  executable; cases launched separately.
- **Browser:** Chrome 154 on the same M4 Pro, production Kotlin/JS module, measured
  in an actual browser. This is distinct from July's Node/V8 run.
- **Cross-platform harness:** 10,000 codec primes, 5,000 per-operation warmups,
  median of seven batches of 3,000 operations, escaped results and equality/checksum
  guards. These medians have less statistical rigor than the JVM JMH results.
- **Baselines:** kotlinx.serialization JSON and ProtoBuf, `encodeDefaults=true`.
  Main JSON encode returns a String; a separate measurement includes UTF-8 output.
  kotlinx ProtoBuf uses default unpacked numeric repeated fields. A supplementary
  Google Java Protobuf comparison uses packed numeric fields.
- **Adversarial cases:** the existing minimum-of-three smoke harness gives
  directional evidence. It is not interchangeable with JMH.

[Download the published machine-readable ledger](https://reaktor.build/docs/files/flexbuffers/performance-review-2026-10-03.json).
It includes samples, ranges, wire sizes, adversarial cases, profiles and test totals.
The retained local source artifacts are in
`reaktor-context/flexbuffer-review-2026-10-03/`: `jvm-jmh.json`,
`browser-results.json`, Android/iOS logs, `google-proto/`, `cpp-native.log`, profiles,
and test XML. No simulator or emulator was used for this review's mobile results.

## JVM encoding and decoding {#jvm}

Microseconds per operation, mean ± 99.9% error estimate. Generated encode copies the
finished result into a returned ByteArray; caller-owned encode returns a borrowed
view. Decode reconstructs the requested Kotlin model. Accessor rows read encoded
fields without full reconstruction.


| Operation | UserProfile | ApiResponse | TimeSeries |
| --- | --- | --- | --- |
| Generated encode | 0.487 ± 0.007 | 3.476 ± 0.089 | 0.376 ± 0.019 |
| Caller-owned encode | 0.612 ± 0.027 | 3.261 ± 0.031 | 0.269 ± 0.003 |
| Generated decode | 0.276 ± 0.004 | 1.715 ± 0.020 | 0.165 ± 0.005 |
| Generated accessor | 0.073 ± 0.001 | 0.582 ± 0.037 | 1.007 ± 0.024 |
| Generic accessor | 0.287 ± 0.004 | 2.507 ± 0.078 | 1.051 ± 0.021 |
| JSON encode (String) | 0.605 ± 0.018 | 8.328 ± 0.165 | 14.607 ± 0.257 |
| JSON decode | 1.442 ± 0.225 | 14.150 ± 0.566 | 26.340 ± 0.539 |
| kotlinx ProtoBuf encode | 0.790 ± 0.024 | 6.045 ± 0.093 | 6.068 ± 0.352 |
| kotlinx ProtoBuf decode | 1.385 ± 0.125 | 8.727 ± 0.324 | 7.372 ± 0.270 |


Generated decode wins each of these three cells against both kotlinx baselines.
Numeric TimeSeries is the strongest case. Caller-owned encoding reduces bytes
allocated, but is **slower** than copying encode for UserProfile in this run
(0.612 versus 0.487 µs). Removing a copy does not guarantee a runtime win.

Allocated bytes per operation, GC profiler; tiny residual values are profiler noise,
not a literal fractional allocation:


| Operation | UserProfile | ApiResponse | TimeSeries |
| --- | --- | --- | --- |
| Generated encode | 1144.007 | 7320.048 | 4360.005 |
| Caller-owned encode | 344.008 | 192.045 | 0.004 |
| Generated decode | 2112.004 | 16304.024 | 4288.002 |
| Generated accessor | 624.001 | 5400.008 | 184.014 |
| Generic accessor | 656.004 | 6040.034 | 184.014 |
| JSON encode (String) | 1104.008 | 31400.115 | 38016.202 |
| JSON decode | 3472.020 | 32880.196 | 65808.364 |
| kotlinx ProtoBuf encode | 5936.011 | 62344.083 | 21160.083 |
| kotlinx ProtoBuf decode | 7016.019 | 38768.120 | 22304.101 |


Full generated decode still allocates model objects, strings and collections.
Caller-owned output only avoids the final byte copy. Borrowed views expire when
the builder is cleared or reused; asynchronous consumers require ownership transfer
or a copy. Do not describe either API as universally allocation-free.

## Matched cross-platform codec results {#platforms}

All values are median µs/op. Flex encode returns copied bytes. `JSON E` returns a
String, `JSON UTF-8 E` returns transport bytes, and `JSON D` reconstructs a Kotlin
model. ProtoBuf also returns bytes and reconstructs a Kotlin model. These fixtures
passed equality/checksum guards; the ChatThread validator rejects a valid empty map
inside the fixture, discussed below.


### JVM supplemental harness


| Case | Flex E | Flex D | JSON E | JSON UTF-8 E | JSON D | Proto E | Proto D |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UserProfile | 0.627 | 0.496 | 0.693 | 0.754 | 1.422 | 1.420 | 1.822 |
| ChatThread | 1.888 | 0.829 | 3.170 | 4.944 | 6.451 | 5.488 | 7.584 |
| ApiResponse | 3.712 | 1.830 | 7.928 | 13.384 | 14.763 | 7.297 | 10.259 |
| TimeSeries | 0.372 | 0.173 | 14.912 | 15.101 | 27.732 | 8.633 | 10.585 |

### Physical Android


| Case | Flex E | Flex D | JSON E | JSON UTF-8 E | JSON D | Proto E | Proto D |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UserProfile | 22.687 | 16.650 | 19.827 | 18.466 | 45.473 | 35.192 | 46.885 |
| ChatThread | 68.161 | 23.631 | 95.724 | 103.417 | 200.875 | 146.672 | 183.575 |
| ApiResponse | 170.600 | 67.923 | 216.632 | 228.657 | 475.280 | 169.542 | 197.218 |
| TimeSeries | 25.502 | 14.830 | 449.213 | 449.142 | 546.730 | 129.299 | 172.468 |

### Physical iOS


| Case | Flex E | Flex D | JSON E | JSON UTF-8 E | JSON D | Proto E | Proto D |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UserProfile | 2.937 | 1.102 | 3.102 | 5.048 | 3.995 | 4.555 | 6.016 |
| ChatThread | 9.878 | 4.568 | 17.229 | 26.513 | 20.535 | 23.636 | 30.841 |
| ApiResponse | 26.339 | 14.932 | 34.384 | 57.756 | 44.781 | 35.880 | 47.226 |
| TimeSeries | 3.468 | 0.269 | 66.032 | 82.351 | 115.949 | 26.636 | 38.172 |

### Chrome browser


| Case | Flex E | Flex D | JSON E | JSON UTF-8 E | JSON D | Proto E | Proto D |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UserProfile | 15.467 | 3.133 | 3.700 | 6.300 | 3.067 | 19.100 | 7.700 |
| ChatThread | 47.233 | 7.233 | 17.967 | 30.267 | 17.500 | 65.800 | 42.233 |
| ApiResponse | 85.267 | 14.400 | 46.733 | 79.267 | 36.933 | 143.600 | 57.567 |
| TimeSeries | 17.467 | 3.000 | 21.467 | 41.233 | 41.933 | 147.133 | 95.033 |


The ranking is workload- and direction-specific. Android UserProfile JSON encoding
beats generated Flex encoding; Android ApiResponse ProtoBuf encoding is marginally
faster than Flex in this harness. In Chrome, JSON encodes the three object-heavy
fixtures faster, and UserProfile JSON decoding is roughly tied/slightly faster.
July's statement that generated decode wins every Node cell does not describe this
browser campaign. TimeSeries remains a clear Flex strength across targets.

### Native browser JSON is a separate representation

These medians measure native `JSON.stringify`/`JSON.parse` on JavaScript objects.
They do not reconstruct Kotlin classes or perform Kotlin collection/Long conversion.
UTF-8 variants include TextEncoder/TextDecoder; the fixture integers fit JavaScript's
safe integer range. They demonstrate the browser's native object-path advantage,
but are not a substitute for the typed-model comparison above.


| Case | Native String E | Native UTF-8 E | Native String D | Native UTF-8 D |
| --- | --- | --- | --- | --- |
| UserProfile | 0.300 | 1.033 | 0.733 | 0.900 |
| ChatThread | 8.700 | 13.100 | 2.733 | 3.700 |
| ApiResponse | 3.600 | 13.433 | 5.667 | 8.167 |
| TimeSeries | 5.933 | 9.467 | 5.933 | 6.933 |


## Wire sizes {#wire}

Bytes per fixture. JVM, Android and iOS agree on all three formats. Browser JSON
number formatting changes the two indicated sizes; Flex and ProtoBuf sizes agree.


| Case | Flex | JSON UTF-8 (JVM/mobile) | JSON UTF-8 (Chrome) | kotlinx ProtoBuf |
| --- | --- | --- | --- | --- |
| UserProfile | 833 | 710 | 710 | 473 |
| ChatThread | 3174 | 3386 | 3386 | 1888 |
| ApiResponse | 7111 | 8546 | 8770 | 5822 |
| TimeSeries | 4340 | 5835 | 5807 | 4157 |


Flex is not always smaller than JSON: UserProfile is 833 versus 710 bytes.
All four kotlinx ProtoBuf fixtures are smaller than Flex. Generated shared-key
blocks improve repeated maps; they do not establish universal size superiority.

## Packed Google Java Protobuf {#google-protobuf}

The supplemental Java harness measures two fresh forks, seven measured batches per
fork, warmed operations and escaped results. Each cell below lists fork 1 / fork 2
median µs/op. It reruns Flex in the same harness; compare within this table rather
than against JMH. Google Protobuf's native model and Kotlin-model conversion are
separate rows.


| Operation | UserProfile | TimeSeries |
| --- | --- | --- |
| Flex encode | 0.631 / 0.589 | 0.378 / 0.379 |
| Flex decode | 0.313 / 0.287 | 0.175 / 0.176 |
| Google encode (native proto model) | 0.431 / 0.403 | 1.042 / 1.023 |
| Google encode (from Kotlin model) | 0.822 / 0.817 | 3.421 / 4.025 |
| Google decode (native proto model) | 0.755 / 0.765 | 1.305 / 1.299 |
| Google decode (to Kotlin model) | 0.746 / 0.773 | 1.301 / 1.276 |


Protoc 33.1 and Google Java runtime 4.36.1 produced 473-byte UserProfile and
3,651-byte packed TimeSeries (versus Flex's 833 / 4,340 bytes). Native Google
UserProfile encoding can beat Flex; conversion to/from Kotlin adds work. Packing
reduces the numeric baseline's size and avoids attributing an unpacked-kotlinx
ProtoBuf limitation to Protobuf as a format. This comparison covers only the two
explicitly modeled fixtures, not mobile or browser Google runtimes.

## In-memory access and validation {#memory}

Median µs/op. Projection consumes selected fields; `Object` starts from an already
materialized model. It excludes the cost of decoding that model and does not serve
as a serialization baseline. Very short operations have limited timer resolution.


| Runtime | Case | Flex projection | Object projection | Structural validation |
| --- | --- | --- | --- | --- |
| JVM | UserProfile | 0.066 | 0.013 | 0.418 |
| JVM | ChatThread | 0.347 | 0.113 | 0.252 |
| JVM | ApiResponse | 0.549 | 0.105 | 2.382 |
| JVM | TimeSeries | 0.652 | 0.153 | 0.237 |
| Android | UserProfile | 6.715 | 0.843 | 8.564 |
| Android | ChatThread | 14.823 | 0.760 | 3.300 |
| Android | ApiResponse | 13.537 | 0.713 | 31.347 |
| Android | TimeSeries | 30.909 | 9.884 | 1.420 |
| iOS | UserProfile | 0.094 | 0.008 | 0.694 |
| iOS | ChatThread | 3.384 | 0.104 | 0.359 |
| iOS | ApiResponse | 6.201 | 0.144 | 5.338 |
| iOS | TimeSeries | 0.964 | 3.014 | 0.256 |
| Chrome | UserProfile | 0.467 | 0.033 | 4.267 |
| Chrome | ChatThread | 2.633 | 0.067 | 2.133 |
| Chrome | ApiResponse | 3.233 | 0.100 | 34.300 |
| Chrome | TimeSeries | 8.467 | 3.433 | 1.433 |


Encoded access avoids paying full decode when a consumer needs a few fields. It
does not usually beat direct field access on an object that already exists.
Repeated vector wrapper construction/traversal can dominate an accessor; generate
count/element/cursor operations and benchmark the actual traversal.

Validation is material work: Chrome ApiResponse validation costs 34.300 µs versus
14.400 µs for trusted generated decode. Validate once at a defined ingress, cache
that result only while bytes remain immutable, and independently check schema/layout.
ChatThread validation exits on a defect, so its short validation time is **not**
successful full validation throughput.

## Android primitive bulk transfer: measured experiment {#android-bulk}

The current Android `PrimitiveArrayCopy.android.kt` implementations all return
`false`; production readers/writers fall back to scalar loops. The following
4,096-element A/B measures standalone scalar versus `java.nio` ByteBuffer bulk
views on the physical S23. It supports a proposed kernel policy, not a shipped
optimization or an equivalent whole-model speedup.


| Primitive | Direction | Scalar µs | ByteBuffer µs | Speedup |
| --- | --- | --- | --- | --- |
| short | encode | 6.106 | 2.569 | 2.38x |
| short | decode | 6.235 | 2.321 | 2.69x |
| int | encode | 41.138 | 2.248 | 18.30x |
| int | decode | 39.837 | 2.340 | 17.02x |
| long | encode | 41.226 | 3.332 | 12.37x |
| long | decode | 40.051 | 3.373 | 11.88x |
| float | encode | 105.495 | 2.230 | 47.31x |
| float | decode | 101.387 | 2.304 | 44.00x |
| double | encode | 105.844 | 3.222 | 32.85x |
| double | decode | 103.570 | 3.362 | 30.81x |


ART exposes `Unsafe` in this run but not object-to-object `copyMemory`. Use public
bulk views rather than assuming HotSpot intrinsics transfer to ART. Measure crossover
sizes per type and direction before choosing thresholds. The historical suggestion
to keep all Shorts scalar needs reevaluation: the October large-Short result also
improves. Small arrays can still lose to wrapper/setup overhead. Preserve compact
wire-width and endianness rules; a natural-width copy is not legal for narrowed data.

## Adversarial and profiling findings {#hotspots}

The complete ledger retains all 11 smoke cases per target, including unsupported
generated cells as `-1` rather than silently omitting them. Those minimum-of-three
timings identify targets for better measurements:

- Chrome string-heavy and graph/object-heavy encode still loses to JSON. Unicode
  and StringFlood decode also lose in the browser; do not claim universal decode wins.
- Unicode encoding is a recurring weakness across platforms. Measure single-pass
  UTF-8 generation, safe capacity slack and removal of temporary arrays before
  selecting a kernel. A native crossing per string can erase any local gain.
- Raw ApiResponse CPU samples include `Map.keyEquals` (17.6% of leaf samples) and
  `FlexDecoderV2.decodeElementIndex` (10.8%); its allocation samples include byte
  arrays (48.7%) and strings (12.4%). Sample shares depend on the profiled workload
  and are not percentages of universal codec cost.
- The native C++ smoke harness passes checksum guards, but its index/key/partial
  reads do not reconstruct Kotlin objects. Fixed-order binary, raw POD and raw-array
  counter-baselines can beat Flex while giving up self-description. Do not divide
  their timings by full Kotlin decode and call the result a language comparison.
- C++ unique-string sharing is 126.246 µs versus 57.799 µs without sharing in the
  native smoke run, trading bytes (33,852 versus 37,128) for hashing work. Reuse
  defaults need fixture-specific A/Bs rather than universal interning.

## Reproduced stability defects {#stability}

The dedicated review probe suite ran five tests: four failures and one pass. These
are distinct from the normal test suite totals retained in the JSON ledger.

| Probe | Observed behavior | Required correction |
| --- | --- | --- |
| `mapViewHashCodeMustMatchEqualMap` | Equal map view hashes to `-1640697897` rather than `192` | Stable entry equality/hash and Map contract; regressions in sets and map keys |
| `validatorMustAcceptValidEmptyMap` | Valid `00000100002401` rejected; upstream C++ verifier accepts it | Correct empty-map zero-offset handling with bounded structural validation |
| `explicitNullMustNotBeReplacedWithDefault` | `NullDefault(text=null)` becomes the fallback | Represent present null separately from absent field before default handling |
| `generatedDecoderMustRejectMissingRequiredField` | A missing-field layout produces a wrong `BenchAddress` rather than rejecting | Required-field/layout checks and a fingerprint/keyed compatibility gate |
| `sameCountDifferentKeysMustUseFieldNames` | Passed | Retain the passing keyed-decoder case; it does not prove generated layout safety |

The required-field failure crosses field values (`street=Zip`, `zip=Wrong street`).
It is data corruption, not a performance tradeoff. Structural validation cannot
prevent reading a structurally valid but semantically incompatible schema.

Other source risks remain: unsynchronized cold writes to the raw decoder's field
index cache, retained giant pooled buffers, registry writes that must remain startup
only, unsupported KSP shapes reaching runtime TODO/error branches, and incomplete
upstream reader parity. Fingerprinting and a versioned production FFI transport are
still absent. No documentation refresh fixes these implementation defects.

## Optimization and adoption plan {#plan}

1. **Correctness and lifetime gate.** Fix the four repros, cache concurrency and pool
   retention; fail compilation for unsupported shapes. Add deterministic wire and
   model semantics tests, malformed-input probes and native sanitizer coverage.
2. **Compile the layout contract.** Extend KSP first: stable schema fingerprints,
   required/default/null semantics, keyed fallback, one-time validation/layout proof,
   generated traversal and no descriptor/key work on the trusted hot path. Investigate
   a compiler plugin when KSP cannot expose or specialize the required language shape;
   account for compile time, generated size, incremental builds and supported targets.
3. **Ship the measured Android opportunity.** Implement public bulk-view kernels,
   benchmark thresholds and release APKs on physical devices, and verify narrowed
   widths, offsets, endianness and boundary sizes. Keep the measured experiment distinct
   from the eventual production A/B.
4. **Fix JS object encode and UTF-8.** Use 32-bit offset/length metadata where wire
   bounds permit it; reserve true 64-bit handling for values. Measure native typed-array
   views, direct byte output and single-pass string encoding. Include browser and Worker
   runtimes, safe-integer/BigInt semantics and actual transport conversion.
5. **Optimize actual consumers.** Use borrowed buffers and generated partial traversal
   only with explicit lifetimes. Measure allocation, retained memory, cold/warm latency,
   p95/p99 and throughput, including validation, queueing and scheduling where relevant.
6. **Move bounded work to C++ when it wins end to end.** Candidate work includes large
   UTF-8/vector batches, scan/filter kernels and SIMD. Cross once per batch or operation;
   include JNI/cinterop/JSI setup, copies, pinning, ownership, cancellation, code size and
   platform fallback. Neither this run nor current FFI code proves a blanket C++ win.
7. **Prototype mutable execution memory separately.** Use the book's
   [chapters 24–30](https://reaktor.build/docs/flatbuffers-flexbuffers-mutable-memory#chapter-24) as a design
   input: cells, descriptors, handles, slabs, SIMD layouts, transactions, snapshots and
   concurrency. Measure pointer/object graphs, packed arrays and Flex views against the
   prototype. Serialize an immutable FlexBuffer snapshot at the boundary; do not treat
   borrowed wire bytes as an arbitrary growable mutable heap.

Performance is judged per workload across encode, full decode, partial read,
in-memory mutation/traversal, bytes, allocations and retained memory. The mutable
engine has no measured implementation yet; its performance is an experiment to run,
not a number to infer from this codec ledger.

## Related documentation

- [FlexBuffers implementation](https://reaktor.build/docs/flexbuffer-implementation): APIs, source map, ownership and historical July evidence.
- [From Bytes to Mutable Memory](https://reaktor.build/docs/flatbuffers-flexbuffers-mutable-memory): the entire supplied book and C++ listings.
- [Reaktor FFI](https://reaktor.build/docs/reaktor-ffi): current native proof and proposed typed frames, ownership and acceptance gates.

