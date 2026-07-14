# reaktor-flexbuffer — physical-device verification & remaining-optimization report

Date: 2026-07-13. Companion to `PERFORMANCE_AUDIT.md` (2026-07-11/12): this pass re-ran the
audit's schedules, added the two measurements the audit itself listed as missing (physical
Android and physical iOS), answered its open policy questions with on-device A/Bs, and stress-
tested the implementation with a new 11-case adversarial + realistic suite on all four runtimes.

## Executive summary

1. **The audit holds.** JMH headline ratios, allocation profiles (byte-identical B/op), Node/V8
   rankings, and the emulator/simulator matrices all reproduce. Physical devices land within
   1.1–1.3× (iPhone 14 Pro vs simulator) and 2.4–2.7× (S23 Ultra debug vs API 36 emulator) of
   the audit's numbers with every ranking preserved.
2. **Roadmap item 8 is now answered.** On physical ART (S23 Ultra, Android 16), `java.nio`
   ByteBuffer bulk transfer beats the current scalar kernels by **46–48× (float), 31–32×
   (double), 18–19× (int), 12× (long)** at 4,096 elements, and still wins float/double at 256.
   The scalar-everywhere Android policy is leaving an order of magnitude on the table for
   primitive vectors. Short vectors and small (<~64 element) int/long vectors should stay scalar.
3. **Two audit assumptions are false on real hardware.** `String.value/coder` reflection does
   NOT resolve on the S23's ART (`StringAccess.ON == false`), so the JVM Latin-1 encode fast
   path silently degrades on Android; and `sun.misc.Unsafe.copyMemory(Object,…)` does not exist
   there (`copyMemoryObj == false`), so ByteBuffer views are the only portable bulk mechanism.
4. **The adversarial suite found real weaknesses the 26-model corpus missed.** Non-ASCII string
   handling is the systemic one: generated *encode* loses to kotlinx JSON on Unicode content on
   every platform (JVM 3.7×, V8 3.7×, ART 1.7×, iOS 1.6×), and on iOS generated Unicode *decode*
   is 2.1× slower than JSON — the only decode loss measured anywhere. Cause: two-pass length-
   precompute + per-string NSString/pin costs. On Android the length pre-scan alone costs as
   much as the entire encode pass (measured ~1:1 at every length).
5. **Generated decode remains untouchable.** It won every decode cell on every platform in both
   suites — up to 19× vs raw Flex (boxed primitives), 18–28× vs JSON on primitive-heavy data,
   and 110× vs Kotlin/Native JSON on iOS MetricsScrape.

## Environment

| Tier | Hardware / runtime | Build |
| --- | --- | --- |
| JVM | M-series Mac, Temurin 25.0.2, JMH 1.37 | audit schedule: 3 forks, 5×750 ms wi, 7×750 ms i, `-prof gc` |
| JS | Node 22.18.0 / V8 12.4 | `compileProductionLibraryKotlinJs`, audit schedule |
| Android | **Samsung S23 Ultra (SM-S918B, SD 8 Gen 2), Android 16, physical** | debug instrumentation APK |
| iOS | **iPhone 14 Pro (A16), iOS 26.5, physical** | release `-opt` iosArm64 `bench.kexe` in a signed `.app` |

Battery/thermal state was verified cool before device runs; iPhone rounds were flat
(no thermal drift). All scored Mac runs executed with no concurrent compilation.

## Audit verification (same schedules, same machine class)

JVM JMH headline (µs/op; B/op — allocation columns matched the audit exactly):

| Case | Caller-owned | Direct gen | Serializer-routed | Raw Flex | JSON | ProtoBuf |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| UserProfile | 0.845; 2400 | 0.912; 3256 | 0.882 | 1.784 | 2.108 | 2.350 |
| ApiResponse | 5.984; 16496 | 5.734; 23624 | 5.678 | 15.945 | 25.556 | 16.217 |
| TimeSeries | 0.494; 4288 | 0.566; 8648 | 0.582 | 5.369 | 49.796 | 14.964 |

Absolutes ran 5–15% above the audit (session thermal offset — expected per the audit's own
cross-session warning); every within-run ratio and the "serializer routing is free" claim held.
Node reproduced the audit matrix including the known JS weakness (generated encode loses to
V8-native JSON on string-heavy payloads; generated decode sweeps all six cells).

## Physical-device matrices (new)

### iPhone 14 Pro — release, audit methodology (100k iters × 8 rounds, median of 2–8)

| Case | Encode µs | Decode µs | vs simulator enc/dec |
| --- | ---: | ---: | --- |
| UserProfile | 3.016 | 1.137 | 1.21× / 0.98× |
| ChatThread | 10.612 | 4.585 | 1.29× / 1.21× |
| ApiResponse | 28.858 | 14.797 | 1.30× / 1.30× |
| TimeSeries | 3.831 | 0.300 | 1.15× / 1.09× |

The audit's simulator release matrix transfers to hardware at ~1.1–1.3×. Wire bytes were
identical (UP=833, AR=7111, CT=3174, TS=4340).

### S23 Ultra — CrossPlatformBenchmark steady state (debug APK), µs/op

| Case | Gen enc | Gen dec | Gen RT | Raw RT | JSON RT | RT vs JSON |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| UserProfile | 21.81 | 16.32 | 38.13 | 57.03 | 58.53 | 1.5× |
| ChatThread | 63.17 | 24.99 | 88.17 | 177.17 | 302.90 | 3.4× |
| ApiResponse | 170.25 | 61.47 | 231.73 | 412.24 | 630.72 | 2.7× |
| TimeSeries | 26.48 | 16.16 | 42.64 | 156.54 | 979.23 | 22.9× |

~2.4–2.7× the emulator's absolutes; identical rankings, including JSON's isolated encode win on
UserProfile. On-device MicroBench: `map.getInt` 15 ns vs `map.getString` 510 ns; a 1-byte
`decodeToString` costs ~293 ns — ART string materialization is ~10× the JVM floor and dominates
string-heavy decode on Android. These remain debug-APK numbers; a release-variant instrumented
run (`testBuildType=release`) is still an open gap.

## Android bulk-transfer A/B (audit roadmap item 8 — ANSWERED)

`PrimitiveBulkAbBenchmark` on the S23, scalar (production ld/st loops) vs
`ByteBuffer.order(LITTLE_ENDIAN)` typed-view bulk, ns/op (min of 3 × 20k):

| Width | 256 enc | 256 dec | 4096 enc | 4096 dec |
| --- | ---: | ---: | ---: | ---: |
| Short | **0.24×** (bulk loses) | **0.24×** | 2.36× | 2.72× |
| Int | 1.08× | 1.13× | **19.1×** | **17.7×** |
| Long | 1.05× | 1.04× | **12.1×** | **11.7×** |
| Float | **2.74×** | **3.04×** | **46.2×** | **47.7×** |
| Double | **2.70×** | **2.79×** | **31.0×** | **32.0×** |

(>1× = bulk faster.) ART's scalar float/double path costs ~26 ns/element (`Float.fromBits` per
element, never vectorized) vs ~0.5–0.8 ns bulk. `copyMemory(Object,…)` is absent on this ART, so
the JVM Unsafe kernels cannot port; ByteBuffer views are the portable mechanism (they route to
native memcpy in libcore).

**Recommended policy:** enable ByteBuffer bulk kernels on Android for Int/Long/Float/Double at
n ≥ ~64 and Float/Double additionally at n ≥ ~32; keep Short scalar below ~1024. Behind the
existing `bulkCopy*` seam this is a small, isolated change; re-run this A/B on one Exynos or
Pixel (different libcore JIT behavior is unlikely but cheap to confirm).

### String hypotheses tested on-device

- `StringAccess.ON == false` on physical ART → Android `fastEncodedLength` never takes the
  Latin-1 word-scan; every string encode is the portable per-char loop. The audit's emulator
  diagnostic did not transfer.
- **The UTF-8 length pre-scan costs as much as the full encode pass** (len 5/24/64/256:
  scan 773/243/588/2246 ns vs encode 571/248/601/2277 ns). The generated/builder path runs both →
  Android string encode does ~2× the necessary work. Fix: single-pass encode with worst-case
  (3×len) capacity slack, taking the real length from the encoder's return; no reflection needed.
- Negative result: `String(bytes, ISO_8859_1)` (ART compact-string constructor) is NOT faster
  than `decodeToString` for short ASCII (0.88–1.08× at ≤64 bytes; 1.44× only at 256). The ~300 ns
  short-string cost is allocation floor, not charset work. Hypothesis killed; do not pursue.

## Adversarial + realistic suite (new; runs on all four targets)

11 cases in `AdversarialBenchmarkModels.kt` / `bench/AdversarialBench.kt`: DeepNest (12-level),
WideFlat (80 fields), PrefixKeys (24 long-common-prefix keys), Unicode (6-script), StringFlood
(384 strings + 64-entry map), WidthBomb (8-wide forcing + negative widths), SparseNulls
(raw-only), FeedPage (~29 KB social page), GraphTopology (120 nodes/300 edges), MetricsScrape
(~96 KB, 24×240-point series), SessionEnvelope (150 audit events).

Generated-tier wins were broad and often extreme (all four platforms): WideFlat raw decode is
9× generated (80-key binary search vs positional); MetricsScrape raw decode 19× (boxed
primitives); JSON round trips 2–28× behind on structure/numeric shapes, and up to 110× on iOS.
The suite's value is where generated **lost**:

| Finding | JVM | V8 | ART | iOS |
| --- | ---: | ---: | ---: | ---: |
| Unicode: gen encode vs JSON encode | 3.7× slower | 3.7× | 1.7× | 1.6× |
| Unicode: gen decode vs JSON decode | wins | wins | wins | **2.1× slower** |
| StringFlood: gen encode vs JSON | wins (1.5×) | 3.5× slower | 1.6× slower | 1.4× slower |
| WidthBomb: gen encode vs raw Flex | 1.25× slower | 1.17× | 1.6× faster* | faster |
| DeepNest: gen encode vs JSON | tie | 3.7× slower | 1.6× faster | 1.9× faster |

\*ART/iOS width-scan cost is masked by their higher baseline costs.

Interpretation:

- **Non-ASCII strings are the one systemic loss.** Encode pays length-precompute + encode
  (two passes) vs JSON's single streaming pass; iOS decode additionally pays per-string
  `usePinned` + `NSString.create` + UTF-16 conversion — measurably worse than kotlinx-json's own
  decoder on identical bytes. This folds three audit/verification items into one workstream:
  single-pass string encode (all platforms), pin-once + `CFStringCreateWithBytes` decode (iOS),
  and the Android bulk/string ports above.
- **JS encode is worse than the 3-case matrix suggested**: 2.6–4.1× behind JSON on every
  object/string-bearing shape (WideFlat 4.05× — ~1-2 emulated-Long temporaries per field in the
  builder). The 32-bit write lane (audit item 1) is confirmed as the top JS item; the read side
  is already Int-clean, and JS generated decode beat JSON everywhere.
- **Deep nesting carries avoidable per-object cost** (`keyBlockBase` linear scan per nested
  object) and a wire-size overhead on tiny objects (426 B generated vs 328 B raw on DeepNest —
  per-type shared key vectors dominate when objects are 2 fields each).
- WidthBomb encode overhead (~25% vs raw on JVM) is the price of optimal width selection —
  generated output was smaller (1097 vs 1115 B). Acceptable; not a work item.

## Source-level findings (verification agent, all file:line checked)

Beyond the audit's roadmap, in priority order:

1. **JS 32-bit write lane** (audit item 1, confirmed precisely): every scalar write funnels
   through `Long` — `putW`/`putWRaw` (`FlexBuffersBuilder.kt:92-100`), size prefixes at 9 call
   sites, `setKeyed(Int)` still boxing via `toLong().toULong()` (`:876-878`), `ValueStack.iVals =
   LongArray(64)` (`:1496-1504` — a JS array of boxed Longs), per-element offset recompute
   (`:1460-1475`), `elemWidth(Long)` (`FlexBuffersInternals.kt:264-292`). Fix: Int lane in
   ValueStack + Int variants of putW/writeOffset/elemWidth; 64-bit values keep the Long lane.
2. **Accessor tier rebuilds wrappers per access** (audit item 2, confirmed): generated accessor
   getters allocate `Vector`/`Map` + wrapper per property read (generated coders, e.g.
   `BenchUserProfileFlexCoder.kt:176-180`); `FlexAccessorList.get` allocates a `Map` per element
   (`FlexCollections.kt:82-85`). No `fieldSize`/`fieldAt` statics exist yet. Emit per-field
   static traversal on the coder object (`FlexRead` already has the primitives).
3. **iOS string decode** (audit item 3, now quantified by the Unicode regression): 3 passes +
   2 allocations per ASCII string (`FastDecode.ios.kt:47-61`), pin + objc_msgSend per non-ASCII
   string (`:66-73`), bounds-checked `get*At` loads (`ByteArray.kt ios:73-104`), 21 `usePinned`
   sites in `PrimitiveArrayCopy.ios.kt`. Fix: pin once per generated decode, raw-pointer cursor,
   `CFStringCreateWithBytes` for strings.
4. **Pool retention + a data race the audit missed**: `FlexBufferPool.release()` re-pools
   unconditionally (`FlexBufferPool.kt:56-65`) — one 100 MB encode parks a 100 MB array in a
   global slot forever; pooled decoder retains the last payload via unscrubbed refs
   (`FlexDecoderV2.kt:45, 683-697, 760-762`). And `fieldIndexCache` (`FlexDecoderV2.kt:87`) is a
   plain shared `HashMap` mutated from arbitrary threads during raw decode — unsynchronized
   concurrent put/get (correctness, not just perf). Cap retained capacity, scrub in release,
   make the cache concurrent or copy-on-write.
5. **Generated decode micro-leaks**: `LinkedHashMap(n)` under-capacity guarantees a rehash
   during population for most n (generated coders; fix the KSP emission to Kotlin's
   `mapCapacity` formula); for-in iterator allocation per collection encode (indexed loop when
   static type is `List`).
6. **Android ld16/st16 are byte-composed** (`ByteArray.kt android:105-109,127-130`) while
   width-2 is the common offset width in 1–64 KB payloads; probe `Unsafe.getShort` or VarHandle
   (API 33+) with a fallback.
7. **Production hygiene (audit item 7, worse than worded)**: ~1.9 k LOC of bench models/runners
   in production `commonMain` plus **79 generated coder files (11.7 k LOC)** registered by the
   module registrar (defeats DCE for any consumer calling `ReaktorFlexbufferCoders.register()`);
   `EncodingTestClass.kt` declares types in package `dev.shibasis.reaktor.core`;
   `api(:reaktor-core)` transitively api-exposes serialization-json/protobuf, coroutines,
   datetime — and reaktor-core's server target pulls Spring WebFlux/Exposed (the 61 MiB JMH jar).
   flexbuffer's production code needs only kotlinx-serialization-core. Extract a bench source
   set/module; narrow the dependency.
8. **KSP silent partial coders**: several unsupported-shape branches emit a `TODO` comment
   (silent field drop on encode) instead of failing compilation
   (`FlexCoderProcessor.kt:414,497-500,550-553`) while others `error()` only at runtime decode
   (`:710-711,801,806,882`). Must fail the build — this is the same class of blocker as the
   missing schema fingerprint (audit "Compatibility and safety blockers", still fully open:
   no fingerprint/ExactLayout scaffolding exists in the processor).
9. Minor: `Map.keyAt` allocates `Key` + `CharArray(2)` per entry iteration
   (`FlexBuffers.kt:939-947,1217-1221`, raw/debug tier); `ArrayReadBuffer.getBytes` byte-by-byte
   loop (`Buffers.kt:446-452`, effectively dead — reroute to `copyInto`); dead
   `ByteArray.getString` (`ByteArray.kt common:22-23`); single-slot Native pool under concurrent
   decode (`PerPlatformPool.ios.kt:17-28`).

The audit's claim that no production call site exists was re-verified: only the FFI raw-reader
stub (`reaktor-ffi/FlexPayload.kt`) and the BestBuds dev-screen hello proof consume the module.

## Prioritized roadmap (evidence-weighted)

| # | Item | Platforms | Expected return | Evidence |
| --- | --- | --- | --- | --- |
| 1 | Single-pass string encode (drop length pre-scan; capacity slack) | Android, iOS, JS, JVM | ~2× Android string encode; closes most of the Unicode/StringFlood encode losses | scan≈encode measured 1:1 on S23; Unicode enc loses to JSON everywhere |
| 2 | JS 32-bit builder lane | JS | biggest JS item: encode 2.6–4.1× behind JSON on all object shapes | Node adversarial |
| 3 | Android ByteBuffer bulk kernels (thresholded) | Android | up to 46× on float/double vectors; 12–19× int/long at 4k | S23 A/B |
| 4 | iOS pin-once decode + CFString | iOS | fixes the only decode loss (Unicode 2.1× vs JSON); helps all string decode | iPhone adversarial |
| 5 | Accessor `fieldSize`/`fieldAt` statics | all | med-high for partial reads (audit tier promise not yet real) | agent §2 |
| 6 | Pool retention cap + release scrub + `fieldIndexCache` fix | all | memory + correctness | agent §5 |
| 7 | Bench/`api(:reaktor-core)` extraction + registrar split | all consumers | binary size, dependency hygiene; zero runtime risk | agent §8 |
| 8 | KSP: fail on unsupported shapes; then schema fingerprint (`ExactLayout` vs `CompatibleKeyed`) | all | unblocks persistence/cross-service use of the fast tier | agent §8/audit |
| 9 | Generated `LinkedHashMap` capacity + indexed encode loops | all | small, broad | agent §6 |
| 10 | keyBlockBase per-object scan → cached slot | all | small; visible on deep-nest shapes | JVM DeepNest tie w/ JSON |
| 11 | Release-variant Android instrumented run (`testBuildType=release`) | Android | measurement gap, not a code change | debug-APK caveat |

Explicitly not worth pursuing: ISO-8859-1 ASCII decode on ART (measured neutral), width-scan
relaxation (costs wire size for ~25% encode on pathological vectors only), Short bulk at small n.

## Reproduction

```bash
# JVM headline (audit schedule) — jar name varies by date; task fails on multiple jars, run directly:
java -Xms1g -Xmx1g -jar reaktor-flexbuffer/build/benchmarks/jvmBenchmark/jars/<today>-JMH.jar \
  ".*FlexBufferJmhBenchmark.*" -f 3 -wi 5 -i 7 -w 750ms -r 750ms -tu us -rf json -prof gc

# Adversarial suite
./gradlew :reaktor-flexbuffer:jvmTest --tests "*.AdversarialBenchmarkTest" --rerun
node --input-type=module -e "import('./reaktor-flexbuffer/build/compileSync/js/main/productionLibrary/kotlin/reaktor-reaktor-flexbuffer.mjs').then(m => m.runAdversarialBench())"
ANDROID_SERIAL=<serial> ./gradlew :reaktor-flexbuffer:connectedDebugAndroidTest   # includes A/B + adversarial

# Physical iPhone (FlexBench.app: iosArm64 bench.kexe + Info.plist + wildcard team dev profile,
# codesign with the Apple Development identity; installed as dev.shibasis.flexbench)
./gradlew :reaktor-flexbuffer:linkBenchReleaseExecutableIosArm64
xcrun devicectl device process launch --console --terminate-existing --device <uuid> \
  --environment-variables '{"BENCH_CASE":"adversarial"}' dev.shibasis.flexbench
# or per-cell: BENCH_CASE=userprofile|chatthread|apiresponse|timeseries, BENCH_OP=encode|decode,
#              BENCH_ITERS=100000, BENCH_ROUNDS=8
```

Raw logs/JSON for this pass: `build/reports/benchmarks/full-2026-07-13/` (JMH JSON + log, Node
matrix log); instrumented results in `build/outputs/androidTest-results/connected/debug/`;
iPhone logs under the session scratchpad (`iphone-bench/*.log`).

## Files added in this pass

- `src/commonMain/.../AdversarialBenchmarkModels.kt` — 11 case families + `AdversarialData`
- `src/commonMain/.../bench/AdversarialBench.kt` — cross-platform runner
- `src/commonTest/.../AdversarialBenchmarkTest.kt` — jvm/js/android/sim entry
- `src/jsMain/.../bench/JsAdversarialBench.kt` — `runAdversarialBench` production export
- `src/iosMain/.../bench/IosBench.kt` — `BENCH_CASE=adversarial` mode (edit)
- `src/androidInstrumentedTest/.../PrimitiveBulkAbBenchmark.kt` — bulk + string A/Bs
- `build.gradle.kts` — `iosArm64` bench executable (edit)

Note: the new bench models follow the existing commonMain precedent for KSP reach; they add to
the hygiene debt in roadmap item 7 and should move with the rest of the bench sources when that
extraction happens. FlexBench.app remains installed on the iPhone for future runs.
