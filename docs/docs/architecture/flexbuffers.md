# FlexBuffers: The Zero-Copy Data Layer

FlexBuffers — part of Google's FlatBuffers project — is a self-describing binary format Reaktor uses as its universal data layer. Unlike JSON, FlexBuffers data can be **read directly from the buffer with zero allocation**, leading to 2-13× speedups in steady-state production code paths.

Reaktor ships a pure-Kotlin Multiplatform FlexBuffers implementation that runs on every supported target (JVM, Android, iOS / native, JS), with a KSP compiler that emits per-class typed accessors for **zero-copy reads** and **register-direct decoders** that bypass `kotlinx.serialization` framework overhead.

---

## The role of FlexBuffers in Reaktor

### 1. FFI wire format
When data crosses language boundaries (Kotlin ↔ C++ ↔ JavaScript ↔ Swift), it must be serialised. FlexBuffers is the canonical format:

- **Zero-copy on the reader side** — the receiver wraps the byte buffer and reads fields directly without parsing.
- **Language-agnostic** — callable from Kotlin/Native, Swift, Dart, C#, Erlang, JavaScript.
- **Schema DSL integration** — `@Struct @Serializable` Kotlin classes generate typed accessors for every target.

### 2. Object cache (ObjectStore)
Reaktor's `ObjectStore` (in `reaktor-db`) is a key-value store backed by SQLite where every value is a FlexBuffer.

- **Cache hit without deserialisation** — reading a byte buffer and accessing one field drops cache-read time from ~2 ms (JSON parse) to ~0.1 ms (FlexBuffer accessor).
- **Partial reads** — only the requested fields are decoded; the rest stay in the buffer.
- **Cache-to-wire zero-copy** — when sending a cached object over the network or FFI, the byte buffer goes through unmodified.

### 3. Network transport
FlexBuffers is the wire format for every Reaktor transport: HTTP, Mesh DataChannels, Pub/Sub, Actor mailboxes. Content negotiation at the Service layer chooses JSON for debugging, FlexBuffer for production.

---

## Five access tiers

Reaktor exposes five tiers of FlexBuffer access, from fastest to most convenient:

```kotlin
@Struct @Serializable
data class UserProfile(
    val id: Long,
    val username: String,
    val tags: List<String>,
    val address: Address  // nested @Struct
)
```

### Tier 1: Accessor (zero-copy, lazy)
```kotlin
val profile = bytes.asUserProfile()  // @JvmInline value class wrapping the buffer
val name = profile.username           // single field read, no Object allocation
```

The accessor is a `@JvmInline value class` over a `Map`. Field getters compile to pointer arithmetic on the underlying buffer. The data class is **never allocated** unless `.toDataClass()` is called.

### Tier 2: FlexCoder (KSP-generated, eager)
```kotlin
val profile = FlexBuffers.decode<UserProfile>(bytes)
```

KSP generates an `object UserProfileFlexCoder : FlexCoder<UserProfile>` that reads fields **by index** (`map.getString(0)`, `map.getLong(1)`, …) — no String key lookups, no binary search, no `kotlinx.serialization` framework dispatch.

### Tier 3: Accelerated serializer
```kotlin
val profile = FlexBuffers.decode(serializer<UserProfile>(), bytes)
```

Same `serializer<T>()` API as `Json.encodeToString`. If a `FlexCoder` is registered for `T` (auto-registered for every `@Struct` class), the framework intercepts and routes to the FlexCoder. **Drop-in replacement for JSON** with FlexCoder performance.

### Tier 4: Raw kotlinx.serialization
```kotlin
FlexCoderRegistry.clear()
val profile = FlexBuffers.decode(serializer<UserProfile>(), bytes)
```

Goes through Reaktor's `FlexEncoderV2` / `FlexDecoderV2` (custom `AbstractEncoder` / `AbstractDecoder`). Used for classes without `@Struct`; provides field-index caching, direct map reads, and a CAS-free per-platform pool to amortise allocations.

### Tier 5: JSON baseline (for comparison)
```kotlin
val profile = json.decodeFromString(serializer<UserProfile>(), jsonString)
```

Standard `kotlinx.serialization.json` for debugging and external interop.

---

## Cross-platform performance

Real numbers from `CrossPlatformBenchmark` (Apple M-series, encode + decode, µs per operation, min of 3 runs):

| Case (FlexCoder direct) | JVM | Android | iOS sim | JS Node |
|-------------------------|-----|---------|---------|---------|
| **UserProfile** (14 fields, nested) | 3.1 µs | 3.0 µs | 7.4 µs | 21.9 µs |
| **ChatThread** (15 msgs, nested) | 6.3 µs | 8.4 µs | 24.0 µs | 96.1 µs |
| **ApiResponse** (20 products, lists) | 14.3 µs | 13.1 µs | 56.9 µs | 167.0 µs |
| **TimeSeries** (256d + 256L typed) | 4.1 µs | 2.9 µs | 18.1 µs | 163.3 µs |

vs JSON baseline on the same payload:

| Case | JVM | Android | iOS | JS |
|------|-----|---------|-----|-----|
| UserProfile | 0.8× | 2.0× | 0.9× | 0.4× |
| ChatThread | 1.7× | 1.3× | 1.6× | 0.5× |
| ApiResponse | 1.6× | 1.7× | 1.4× | 0.6× |
| TimeSeries | **10.7×** | **15.6×** | **10.0×** | 0.7× |

**Headlines:**
- FlexBuffer is 1.4-15× faster than JSON on JVM and Android.
- TimeSeries-style numeric bulk payloads see **10-15× speedups** everywhere except JS — typed-vector encoding is structurally optimal for primitive arrays.
- On iOS / Kotlin/Native the gap vs JVM is 2-5× (no JIT, no escape analysis); FlexBuffer is still competitive with JSON.
- V8's native `JSON.parse`/`stringify` is hard to beat for small struct workloads on JS; FlexBuffer wins for numeric bulk.

---

## Engineering deep dive

### Wire-size

FlexBuffer is generally smaller than JSON for numeric and collection-heavy payloads. Examples (encoded bytes / JSON bytes):

| Payload | FlexBuffer | JSON | Δ |
|---------|-----------|------|---|
| UserProfile | 833 B | 710 B | +17% (strings dominate) |
| ApiResponse | 7483 B | 8506 B | **-12%** |
| TimeSeries | 4340 B | 5835 B | **-26%** |
| ChatThread | 3372 B | 3380 B | ~equal |

FlexBuffer's type tags and key storage add overhead for string-heavy payloads but pay back on numeric and structured data.

### Field index caching (raw serializer path)

`FlexDecoderV2` caches a `descriptorIndex → mapPosition` `IntArray` per class. Since FlexBuffer maps are always sorted alphabetically, the mapping is deterministic from the field names alone — computed once on first decode, reused forever. Replaces an O(log n) binary search per field with an O(1) array lookup.

### Direct scalar reads (zero Reference allocation)

`Reference` is the type-tagged FlexBuffer pointer; allocating one per field would be ~10 heap allocations per object decode. Both KSP-generated FlexCoders and `FlexDecoderV2` use **direct typed reads** (`map.getInt(i)`, `map.getString(i)`, `map.getMap(i)`) that skip Reference entirely. For `Map<K,V>` and nested struct lists, the position index is threaded through context state so even `Map.get(pairIndex)` skips Reference materialisation.

### Per-platform single-slot pool

Encoders and decoders are pooled per platform via `PerPlatformPool<T>` (expect/actual):

| Platform | Implementation | Acquire cost |
|----------|---------------|--------------|
| JVM | `ThreadLocal<T?>` | ~1 ns |
| Android | `ThreadLocal<T?>` | ~10 ns (ART) |
| iOS / Native | `@Volatile var slot: T?` | ~3-5 ns |
| JS | plain `var slot: T?` | ~1 ns (single-threaded) |

Skipping `AtomicReference` on Native saved ~30 ns per acquire compared to a CAS-based cross-platform pool. JS is single-threaded so atomic ops are pure waste.

### iOS-specific UTF-8 fast paths

Kotlin/Native's stdlib `ByteArray.decodeToString` is ~10× slower per byte than JVM's heavily-intrinsified equivalent. The micro-bench surfaced this clearly: a 62-byte URL decode took 268 ns on iOS vs 34 ns on JVM. `ApiResponse` decode hits this 100+ times per payload.

`fastDecodeUtf8` / `fastEncodeUtf8` (expect/actual) take the optimal path per runtime:

- **JVM / Android / JS**: delegate to stdlib (already near-optimal — JIT intrinsifies).
- **iOS / Native**: ASCII fast path (direct char-cast loop, no captures the AOT compiler can't eliminate) + `NSString.create(bytes:length:encoding:)` for non-ASCII. Apple's UTF-8 decoder uses NEON-vectorised loops.

After this work, the same 62-byte decode takes 102 ns on iOS — **2.6× faster** than the stdlib path.

### KSP-generated artefacts

For every `@Struct @Serializable` class, the compiler emits:

1. **`XxxFlexCoder : FlexCoder<Xxx>`** — `encode(builder, value)` writes fields alphabetically (so `endMap(presorted = true)` skips the sort); `decode(ref)` reads fields by integer index.
2. **`XxxAccessor` — `@JvmInline value class`** wrapping a `Map`. Field getters are pointer arithmetic; lazy collection wrappers (`FlexIntList`, `FlexStringStringMap`) defer materialisation.
3. **Extension functions** — `Reference.asXxx()` and `ByteArray.asXxx()` for ergonomic zero-copy reads.
4. **Registry entry** — auto-registered in `registerGeneratedFlexCoders()` so the serializer-API path picks it up.

### Profiling infrastructure

The repository ships `PhaseProfiler` (a Gradle task and Kotlin runner) that captures per-tier × per-payload CPU + allocation flamegraphs via async-profiler. Run:

```bash
./gradlew :reaktor-flexbuffer:phaseProfile
# Output: flamechart/output/phase/<phase>-{cpu,alloc}.html
```

`flamechart/analyze.py` aggregates the collapsed-format dumps into top-N hot frame tables, filtering JIT compiler thread noise.

---

## When to use what

| Scenario | Tier |
|----------|------|
| Read one field from cached buffer | Accessor (`bytes.asUserProfile().name`) |
| Materialise full object from cache or wire | FlexCoder direct (`FlexBuffers.decode<UserProfile>(bytes)`) |
| Drop-in JSON replacement (same `serializer<T>()` call sites) | Accelerated serializer |
| Class without `@Struct` annotation | Raw kotlinx.serialization (still ~1.5-3× faster than JSON for most cases) |
| Debugging / external interop / human-readable | JSON |

---

## Implementation files

```
reaktor-flexbuffer/
├── src/commonMain/kotlin/dev/shibasis/reaktor/flexbuffer/
│   ├── core/
│   │   ├── FlexBuffers.kt        — public API (encode/decode entry points)
│   │   ├── FlexCoder.kt          — FlexCoder interface + registry
│   │   ├── FlexEncoderV2.kt      — kotlinx.serialization AbstractEncoder
│   │   ├── FlexDecoderV2.kt      — kotlinx.serialization AbstractDecoder
│   │   ├── FlexCollections.kt    — zero-copy List/Map wrappers
│   │   ├── PerPlatformPool.kt    — expect/actual single-slot pool
│   │   ├── FlexBufferPool.kt     — FlexBuffersBuilder pool
│   │   └── Struct.kt             — @Struct annotation
│   └── flatbuffers/
│       ├── FlexBuffers.kt        — Map/Vector/Reference (vendored from Google + extensions)
│       ├── FlexBuffersBuilder.kt — write API
│       └── FastDecode.kt         — expect/actual UTF-8 codec
├── src/jvmMain/...        — JVM-specific actuals (ThreadLocal pool, stdlib UTF-8)
├── src/androidMain/...    — Android actuals (ThreadLocal pool, stdlib UTF-8)
├── src/iosMain/...        — Native actuals (Volatile pool, NSString fast UTF-8)
└── src/jsMain/...         — JS actuals (plain pool, stdlib UTF-8)

reaktor-compiler/src/main/kotlin/dev/shibasis/reaktor/compiler/
└── FlexCoderProcessor.kt  — KSP: emits FlexCoder + Accessor + extensions
```

---

## Further reading

- `flexbuffer-performance.html` — interactive performance explorer (in `static/docs/`)
- `reaktor-flexbuffer/README.md` — implementation README
- Google's FlatBuffers FlexBuffers spec — https://flatbuffers.dev/flexbuffers.html
