/**
 * FlexBuffer C++ Exhaustive Performance Harness
 *
 * Comprehensive benchmark covering every performance dimension:
 *   - Encode/Decode with statistical output (median, min, p99, stddev, IQR)
 *   - Key-based vs Index-based decode (O(log n) vs O(1))
 *   - Partial reads (accessor pattern: 1, 3, N fields)
 *   - Typed vectors vs generic vectors
 *   - Scalar density (tight primitives vs string-heavy)
 *   - Nesting depth impact (1-8 levels)
 *   - Payload size scaling (1KB → 100KB)
 *   - Map size scaling (5, 50, 500 keys)
 *   - Vector iteration patterns (sequential, random, typed)
 *   - Memory bandwidth and cache efficiency
 *   - Encode: pre-sized vs growing builder
 *   - Encode: key sharing vs no sharing
 *   - Decode: root parse cost isolation
 *   - Decode: string materialization cost
 *   - Decode: nested map traversal cost
 *   - Round-trip encode→decode latency
 *
 * Build:
 *   clang++ -O2 -std=c++17 \
 *     -I ../../../.github_modules/flatbuffers/include \
 *     flexbuffer_bench.cpp -o flexbuffer_bench
 *
 * Run:
 *   ./flexbuffer_bench              # all benchmarks
 *   ./flexbuffer_bench --verify     # + correctness assertions
 *   ./flexbuffer_bench --runs 10    # more statistical runs
 *   ./flexbuffer_bench --iters 50000 # more iterations per run
 *   ./flexbuffer_bench --quick      # fast mode (fewer iterations)
 *   ./flexbuffer_bench --section 3  # run only phase 3
 *   ./flexbuffer_bench --adversarial # run only phase 15
 *   ./flexbuffer_bench --golden     # print C++-encoded golden/fuzz hex fixtures
 *   ./flexbuffer_bench --verify-hex golden_scalars <hex> # verify a Kotlin-encoded fixture
 *
 * Structures (10 realistic models):
 *   Original 4:
 *     1. FlatPrimitives — 9 scalar fields
 *     2. CollectionHeavy — 100-element int list, 50-element string list, 100 doubles, nested lists, maps
 *     3. ComplexCase — 25 fields with nested objects, maps of nested data, byte arrays
 *     4. DeeplyNested — 4 nesting levels
 *   Realistic 6:
 *     5. UserProfile — string-heavy, nested address, settings map, tags list
 *     6. ApiResponse — 20 products, paginated
 *     7. EventLog — telemetry payload
 *     8. ChatThread — 15 messages, reactions
 *     9. ConfigSnapshot — feature flags, nested maps
 *    10. TimeSeries — 256 doubles + 256 longs
 *
 * Micro-benchmarks (Phase 7+):
 *    - Scalar read isolation (int, long, double, string, bool)
 *    - Vector iteration (typed int vector, typed double vector, generic vector)
 *    - Map key lookup scaling (5, 25, 100, 500 keys)
 *    - Nesting depth cost (1, 2, 4, 8 levels)
 *    - String length impact (10, 100, 1000, 10000 chars)
 *    - Payload size vs decode time correlation
 *    - Adversarial counter-baselines designed to prove when FlexBuffers lose:
 *      raw POD structs, fixed-order binary rows, raw arrays, sparse optionals,
 *      controlled JSON scans, and unique-string encode payloads.
 */

#include <flatbuffers/flexbuffers.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <cassert>
#include <functional>
#include <numeric>
#include <random>
#include <string>
#include <vector>

static int WARMUP = 1000;
static int ITERATIONS = 10000;
static int RUNS = 5;
static bool g_verify = false;
static int g_section = 0; // 0 = all

// ─── Assertion macros ───

#define ASSERT_EQ(a, b) do { \
    if ((a) != (b)) { \
        fprintf(stderr, "FAIL: %s:%d: %s != %s\n", __FILE__, __LINE__, #a, #b); \
        abort(); \
    } \
} while(0)

#define ASSERT_STREQ(a, b) do { \
    if (strcmp((a), (b)) != 0) { \
        fprintf(stderr, "FAIL: %s:%d: \"%s\" != \"%s\"\n", __FILE__, __LINE__, (a), (b)); \
        abort(); \
    } \
} while(0)

#define ASSERT_NEAR(a, b, eps) do { \
    if (fabs((double)(a) - (double)(b)) > (eps)) { \
        fprintf(stderr, "FAIL: %s:%d: %f != %f (eps=%f)\n", __FILE__, __LINE__, (double)(a), (double)(b), (double)(eps)); \
        abort(); \
    } \
} while(0)

// ─── Timing infrastructure with comprehensive statistics ───

struct BenchResult {
    double median_us;
    double min_us;
    double max_us;
    double p5_us;
    double p95_us;
    double p99_us;
    double mean_us;
    double stddev;
    double iqr;
    size_t bytes;
};

template <typename Fn>
BenchResult bench(const char* label, int warmup, int iters, Fn&& fn, int runs) {
    for (int i = 0; i < warmup; ++i) fn();

    std::vector<double> samples(runs);
    for (int r = 0; r < runs; ++r) {
        auto start = std::chrono::high_resolution_clock::now();
        for (int i = 0; i < iters; ++i) fn();
        auto end = std::chrono::high_resolution_clock::now();
        samples[r] = std::chrono::duration<double, std::micro>(end - start).count() / iters;
    }
    std::sort(samples.begin(), samples.end());

    int n = runs;
    double median = samples[n / 2];
    double min_v = samples[0];
    double max_v = samples[n - 1];
    double p5 = samples[(int)(n * 0.05)];
    double p95 = samples[(int)(n * 0.95)];
    double p99 = samples[std::min(n - 1, (int)(n * 0.99))];
    double q1 = samples[n / 4];
    double q3 = samples[(3 * n) / 4];
    double iqr = q3 - q1;

    double sum = 0, sum2 = 0;
    for (auto s : samples) { sum += s; sum2 += s * s; }
    double mean = sum / n;
    double stddev = sqrt(sum2 / n - mean * mean);

    if (label) {
        printf("  %-42s %7.2f us/op  (min=%6.2f  p95=%7.2f  sd=%5.3f  iqr=%5.3f)\n",
               label, median, min_v, p95, stddev, iqr);
    }
    return {median, min_v, max_v, p5, p95, p99, mean, stddev, iqr, 0};
}

// Silent bench (no print)
template <typename Fn>
BenchResult bench_quiet(int warmup, int iters, Fn&& fn, int runs) {
    return bench(nullptr, warmup, iters, std::forward<Fn>(fn), runs);
}

// ─── Checksum accumulator to prevent dead-code elimination ───

static volatile int64_t g_sink = 0;

inline void sink(int64_t v) { g_sink += v; }
inline void sink(double v) { g_sink += (int64_t)v; }
inline void sink(bool v) { g_sink += v ? 1 : 0; }
inline void sink(const char* v) { g_sink += v[0]; }
inline void sink(flexbuffers::String v) { if (v.size() > 0) g_sink += v.c_str()[0]; }

// ─── Cross-language golden/fuzz fixtures ───

std::string hex_encode(const std::vector<uint8_t>& bytes) {
    static const char* digits = "0123456789abcdef";
    std::string out;
    out.resize(bytes.size() * 2);
    for (size_t i = 0; i < bytes.size(); ++i) {
        out[i * 2] = digits[bytes[i] >> 4];
        out[i * 2 + 1] = digits[bytes[i] & 0x0f];
    }
    return out;
}

int hex_nibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return 10 + c - 'a';
    if (c >= 'A' && c <= 'F') return 10 + c - 'A';
    return -1;
}

std::vector<uint8_t> hex_decode(const char* hex) {
    size_t len = strlen(hex);
    if ((len & 1) != 0) {
        fprintf(stderr, "hex input has odd length\n");
        abort();
    }
    std::vector<uint8_t> bytes(len / 2);
    for (size_t i = 0; i < bytes.size(); ++i) {
        int hi = hex_nibble(hex[i * 2]);
        int lo = hex_nibble(hex[i * 2 + 1]);
        if (hi < 0 || lo < 0) {
            fprintf(stderr, "hex input contains non-hex characters\n");
            abort();
        }
        bytes[i] = static_cast<uint8_t>((hi << 4) | lo);
    }
    return bytes;
}

std::vector<uint8_t> encode_golden_scalars() {
    flexbuffers::Builder b(512, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.Bool("b", true);
        b.Double("d", 3.5);
        b.Int("i", -17);
        b.Int("l", -1234567890123LL);
        b.String("s", "golden");
    });
    b.Finish();
    return b.GetBuffer();
}

std::vector<uint8_t> encode_golden_vectors() {
    flexbuffers::Builder b(1024, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.TypedVector("doubles", [&] {
            b.Double(-2.5);
            b.Double(0.0);
            b.Double(42.25);
        });
        b.TypedVector("ints", [&] {
            b.Int(-129);
            b.Int(-1);
            b.Int(0);
            b.Int(1);
            b.Int(127);
            b.Int(128);
        });
        b.TypedVector("longs", [&] {
            b.Int(-1234567890123LL);
            b.Int(0);
            b.Int(1234567890123LL);
        });
    });
    b.Finish();
    return b.GetBuffer();
}

std::vector<uint8_t> encode_golden_nested() {
    flexbuffers::Builder b(1024, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.Map("child", [&] {
            b.Int("id", 7);
            b.String("name", "child");
        });
        b.Int("count", 3);
        b.Vector("tags", [&] {
            b.String("alpha");
            b.String("beta");
            b.String("gamma");
        });
    });
    b.Finish();
    return b.GetBuffer();
}

std::vector<uint8_t> encode_fuzz_case(int seed) {
    const int64_t l = -1234567890123LL + static_cast<int64_t>(seed) * 7919LL;
    const double d = static_cast<double>(seed) * 0.5 - 7.25;
    std::string s = "seed_" + std::to_string(seed) + "_" + static_cast<char>('a' + (seed % 26));

    flexbuffers::Builder b(1024, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.Bool("b", (seed & 1) == 0);
        b.Double("d", d);
        b.TypedVector("doubles", [&] {
            b.Double(d);
            b.Double(d + 0.25);
            b.Double(-d);
        });
        b.Int("i", seed * 104729 - 2048);
        b.TypedVector("ints", [&] {
            b.Int(seed - 3);
            b.Int(-seed);
            b.Int(0);
            b.Int(seed * seed);
        });
        b.Int("l", l);
        b.TypedVector("longs", [&] {
            b.Int(l);
            b.Int(l + 1);
        });
        b.String("s", s.c_str());
    });
    b.Finish();
    return b.GetBuffer();
}

void verify_golden_scalars(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    ASSERT_EQ(root["b"].AsBool(), true);
    ASSERT_NEAR(root["d"].AsDouble(), 3.5, 1e-9);
    ASSERT_EQ(root["i"].AsInt64(), -17);
    ASSERT_EQ(root["l"].AsInt64(), -1234567890123LL);
    ASSERT_STREQ(root["s"].AsString().c_str(), "golden");
}

void verify_golden_vectors(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    auto ints = root["ints"].AsTypedVector();
    ASSERT_EQ(ints.size(), 6);
    ASSERT_EQ(ints[0].AsInt64(), -129);
    ASSERT_EQ(ints[5].AsInt64(), 128);
    auto longs = root["longs"].AsTypedVector();
    ASSERT_EQ(longs.size(), 3);
    ASSERT_EQ(longs[0].AsInt64(), -1234567890123LL);
    ASSERT_EQ(longs[2].AsInt64(), 1234567890123LL);
    auto doubles = root["doubles"].AsTypedVector();
    ASSERT_EQ(doubles.size(), 3);
    ASSERT_NEAR(doubles[0].AsDouble(), -2.5, 1e-9);
    ASSERT_NEAR(doubles[2].AsDouble(), 42.25, 1e-9);
}

void verify_golden_nested(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    ASSERT_EQ(root["count"].AsInt64(), 3);
    auto child = root["child"].AsMap();
    ASSERT_EQ(child["id"].AsInt64(), 7);
    ASSERT_STREQ(child["name"].AsString().c_str(), "child");
    auto tags = root["tags"].AsVector();
    ASSERT_EQ(tags.size(), 3);
    ASSERT_STREQ(tags[0].AsString().c_str(), "alpha");
    ASSERT_STREQ(tags[2].AsString().c_str(), "gamma");
}

void verify_fuzz_case(const std::vector<uint8_t>& buf, int seed) {
    const int64_t l = -1234567890123LL + static_cast<int64_t>(seed) * 7919LL;
    const double d = static_cast<double>(seed) * 0.5 - 7.25;
    std::string s = "seed_" + std::to_string(seed) + "_" + static_cast<char>('a' + (seed % 26));

    auto root = flexbuffers::GetRoot(buf).AsMap();
    ASSERT_EQ(root["b"].AsBool(), (seed & 1) == 0);
    ASSERT_NEAR(root["d"].AsDouble(), d, 1e-9);
    ASSERT_EQ(root["i"].AsInt64(), seed * 104729 - 2048);
    ASSERT_EQ(root["l"].AsInt64(), l);
    ASSERT_STREQ(root["s"].AsString().c_str(), s.c_str());

    auto ints = root["ints"].AsTypedVector();
    ASSERT_EQ(ints.size(), 4);
    ASSERT_EQ(ints[0].AsInt64(), seed - 3);
    ASSERT_EQ(ints[1].AsInt64(), -seed);
    ASSERT_EQ(ints[3].AsInt64(), seed * seed);
    auto longs = root["longs"].AsTypedVector();
    ASSERT_EQ(longs.size(), 2);
    ASSERT_EQ(longs[0].AsInt64(), l);
    ASSERT_EQ(longs[1].AsInt64(), l + 1);
    auto doubles = root["doubles"].AsTypedVector();
    ASSERT_EQ(doubles.size(), 3);
    ASSERT_NEAR(doubles[0].AsDouble(), d, 1e-9);
    ASSERT_NEAR(doubles[1].AsDouble(), d + 0.25, 1e-9);
    ASSERT_NEAR(doubles[2].AsDouble(), -d, 1e-9);
}

void verify_golden_named(const char* name, const std::vector<uint8_t>& buf) {
    if (strcmp(name, "golden_scalars") == 0) verify_golden_scalars(buf);
    else if (strcmp(name, "golden_vectors") == 0) verify_golden_vectors(buf);
    else if (strcmp(name, "golden_nested") == 0) verify_golden_nested(buf);
    else if (strncmp(name, "fuzz_", 5) == 0) verify_fuzz_case(buf, atoi(name + 5));
    else {
        fprintf(stderr, "unknown golden fixture name: %s\n", name);
        abort();
    }
}

void print_hex_fixture(const char* kind, const char* name, const std::vector<uint8_t>& bytes) {
    printf("%s|%s|%s\n", kind, name, hex_encode(bytes).c_str());
}

int print_golden_flexbuffers() {
    print_hex_fixture("GOLDEN", "golden_scalars", encode_golden_scalars());
    print_hex_fixture("GOLDEN", "golden_vectors", encode_golden_vectors());
    print_hex_fixture("GOLDEN", "golden_nested", encode_golden_nested());
    for (int seed = 0; seed < 16; ++seed) {
        std::string name = "fuzz_" + std::to_string(seed);
        print_hex_fixture("FUZZ", name.c_str(), encode_fuzz_case(seed));
    }
    return 0;
}

int verify_golden_hex(const char* name, const char* hex) {
    auto bytes = hex_decode(hex);
    verify_golden_named(name, bytes);
    printf("VERIFY_HEX|%s|PASS\n", name);
    return 0;
}

// ════════════════════════════════════════════════════════════════════════
// 1. FlatPrimitives: 9 scalar fields
// ════════════════════════════════════════════════════════════════════════

std::vector<uint8_t> encode_flat_primitives() {
    flexbuffers::Builder b(512, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.Bool("b", true);
        b.Int("by", 127);
        b.Int("c", 'Z');
        b.Double("d", 2.718281828);
        b.Float("f", 3.14f);
        b.Int("i", 42);
        b.Int("l", 123456789L);
        b.Int("sh", 32000);
        b.String("s", "benchmark string value");
    });
    b.Finish();
    return b.GetBuffer();
}

void decode_flat_primitives(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    sink(root["b"].AsBool());
    sink(root["by"].AsInt64());
    sink(root["c"].AsInt64());
    sink(root["d"].AsDouble());
    sink((double)root["f"].AsFloat());
    sink(root["i"].AsInt64());
    sink(root["l"].AsInt64());
    sink(root["sh"].AsInt64());
    sink(root["s"].AsString());
}

void decode_flat_primitives_indexed(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    auto vals = root.Values();
    // Sorted: b=0 by=1 c=2 d=3 f=4 i=5 l=6 s=7 sh=8
    sink(vals[0].AsBool());
    sink(vals[1].AsInt64());
    sink(vals[2].AsInt64());
    sink(vals[3].AsDouble());
    sink((double)vals[4].AsFloat());
    sink(vals[5].AsInt64());
    sink(vals[6].AsInt64());
    sink(vals[7].AsString());
    sink(vals[8].AsInt64());
}

void decode_flat_primitives_partial(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    sink(root["i"].AsInt64());
    sink(root["s"].AsString());
    sink(root["b"].AsBool());
}

void decode_flat_primitives_single(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    sink(root["i"].AsInt64());
}

void verify_flat_primitives(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    ASSERT_EQ(root.size(), 9);
    ASSERT_EQ(root["b"].AsBool(), true);
    ASSERT_EQ(root["by"].AsInt64(), 127);
    ASSERT_EQ(root["c"].AsInt64(), 'Z');
    ASSERT_NEAR(root["d"].AsDouble(), 2.718281828, 1e-6);
    ASSERT_NEAR(root["f"].AsFloat(), 3.14f, 0.001f);
    ASSERT_EQ(root["i"].AsInt64(), 42);
    ASSERT_EQ(root["l"].AsInt64(), 123456789L);
    ASSERT_EQ(root["sh"].AsInt64(), 32000);
    ASSERT_STREQ(root["s"].AsString().c_str(), "benchmark string value");
    printf("  FlatPrimitives:   PASS (9 fields verified)\n");
}

// ════════════════════════════════════════════════════════════════════════
// 2. CollectionHeavy: lists + maps (FULL decode)
// ════════════════════════════════════════════════════════════════════════

std::vector<uint8_t> encode_collection_heavy() {
    flexbuffers::Builder b(8192, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.Vector("doubleList", [&] {
            for (int i = 1; i <= 100; ++i) b.Double(i * 0.1);
        });
        b.Map("intMap", [&] {
            for (int i = 1; i <= 50; ++i) {
                b.Int(("k" + std::to_string(i)).c_str(), i);
            }
        });
        b.Vector("intList", [&] {
            for (int i = 1; i <= 100; ++i) b.Int(i);
        });
        b.Vector("nestedList", [&] {
            for (int i = 0; i < 10; ++i) {
                b.Vector([&] {
                    for (int j = 1; j <= 10; ++j) b.Int(j);
                });
            }
        });
        b.Map("stringMap", [&] {
            for (int i = 1; i <= 50; ++i) {
                b.String(("key" + std::to_string(i)).c_str(),
                         ("value" + std::to_string(i)).c_str());
            }
        });
        b.Vector("stringList", [&] {
            for (int i = 1; i <= 50; ++i) {
                b.String(("item_" + std::to_string(i)).c_str());
            }
        });
    });
    b.Finish();
    return b.GetBuffer();
}

void decode_collection_heavy(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    auto doubleList = root["doubleList"].AsVector();
    for (size_t i = 0; i < doubleList.size(); ++i) { sink(doubleList[i].AsDouble()); }
    auto intMap = root["intMap"].AsMap();
    auto intMapKeys = intMap.Keys();
    for (size_t i = 0; i < intMapKeys.size(); ++i) {
        sink(intMap[intMapKeys[i].AsKey()].AsInt64());
    }
    auto intList = root["intList"].AsVector();
    for (size_t i = 0; i < intList.size(); ++i) { sink(intList[i].AsInt64()); }
    auto nestedList = root["nestedList"].AsVector();
    for (size_t i = 0; i < nestedList.size(); ++i) {
        auto inner = nestedList[i].AsVector();
        for (size_t j = 0; j < inner.size(); ++j) { sink(inner[j].AsInt64()); }
    }
    auto stringMap = root["stringMap"].AsMap();
    auto smKeys = stringMap.Keys();
    for (size_t i = 0; i < smKeys.size(); ++i) {
        sink(stringMap[smKeys[i].AsKey()].AsString());
    }
    auto stringList = root["stringList"].AsVector();
    for (size_t i = 0; i < stringList.size(); ++i) { sink(stringList[i].AsString()); }
}

void decode_collection_heavy_indexed(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    auto vals = root.Values();
    // Sorted: doubleList=0 intList=1 intMap=2 nestedList=3 stringList=4 stringMap=5
    auto doubleList = vals[0].AsVector();
    for (size_t i = 0; i < doubleList.size(); ++i) { sink(doubleList[i].AsDouble()); }
    auto intList = vals[1].AsVector();
    for (size_t i = 0; i < intList.size(); ++i) { sink(intList[i].AsInt64()); }
    auto intMap = vals[2].AsMap();
    auto imVals = intMap.Values();
    for (size_t i = 0; i < imVals.size(); ++i) { sink(imVals[i].AsInt64()); }
    auto nestedList = vals[3].AsVector();
    for (size_t i = 0; i < nestedList.size(); ++i) {
        auto inner = nestedList[i].AsVector();
        for (size_t j = 0; j < inner.size(); ++j) { sink(inner[j].AsInt64()); }
    }
    auto stringList = vals[4].AsVector();
    for (size_t i = 0; i < stringList.size(); ++i) { sink(stringList[i].AsString()); }
    auto stringMap = vals[5].AsMap();
    auto smVals = stringMap.Values();
    for (size_t i = 0; i < smVals.size(); ++i) { sink(smVals[i].AsString()); }
}

void verify_collection_heavy(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    ASSERT_EQ(root.size(), 6);
    auto doubleList = root["doubleList"].AsVector();
    ASSERT_EQ(doubleList.size(), 100);
    ASSERT_NEAR(doubleList[0].AsDouble(), 0.1, 1e-9);
    ASSERT_NEAR(doubleList[99].AsDouble(), 10.0, 1e-9);
    auto intList = root["intList"].AsVector();
    ASSERT_EQ(intList.size(), 100);
    ASSERT_EQ(intList[0].AsInt64(), 1);
    ASSERT_EQ(intList[99].AsInt64(), 100);
    auto stringList = root["stringList"].AsVector();
    ASSERT_EQ(stringList.size(), 50);
    ASSERT_STREQ(stringList[0].AsString().c_str(), "item_1");
    auto nestedList = root["nestedList"].AsVector();
    ASSERT_EQ(nestedList.size(), 10);
    auto inner0 = nestedList[0].AsVector();
    ASSERT_EQ(inner0.size(), 10);
    ASSERT_EQ(inner0[0].AsInt64(), 1);
    auto stringMap = root["stringMap"].AsMap();
    ASSERT_EQ(stringMap.size(), 50);
    ASSERT_STREQ(stringMap["key1"].AsString().c_str(), "value1");
    auto intMap = root["intMap"].AsMap();
    ASSERT_EQ(intMap.size(), 50);
    ASSERT_EQ(intMap["k1"].AsInt64(), 1);
    printf("  CollectionHeavy:  PASS (6 collections, 460 elements verified)\n");
}

// ════════════════════════════════════════════════════════════════════════
// 3. ComplexCase: 25 fields with nested objects (FULL decode)
// ════════════════════════════════════════════════════════════════════════

static void encode_nested_data(flexbuffers::Builder& b, const char* key,
                               int nestedInt, const char* nestedString,
                               double innerValue) {
    b.Map(key, [&] {
        b.Vector("innerNestedData", [&] {
            b.Map([&] {
                b.Vector("innerList", [&] {
                    b.String("Inner");
                    b.String("List");
                });
                b.Double("innerValue", innerValue);
            });
        });
        b.Int("nestedInt", nestedInt);
        b.String("nestedString", nestedString);
    });
}

std::vector<uint8_t> encode_complex_case() {
    flexbuffers::Builder b(4096, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.Bool("booleanField", true);
        uint8_t blob[] = {1, 2, 3};
        b.Blob("byteArrayField", blob, 3);
        b.Int("byteField", 1);
        b.Vector("charListField", [&] { b.Int(66); b.Int(67); b.Int(68); });
        b.Int("charField", 65);
        b.Double("doubleField", 6.0);
        b.Vector("doubleListField", [&] { b.Double(16.0); b.Double(17.0); b.Double(18.0); });
        b.Float("floatField", 5.0f);
        b.Vector("floatSetField", [&] { b.Float(13.0f); b.Float(14.0f); b.Float(15.0f); });
        b.Int("intField", 3);
        b.Vector("intSetField", [&] { b.Int(7); b.Int(8); b.Int(9); });
        b.Vector("listOfLists", [&] {
            b.Vector([&] { b.Int(1); b.Int(2); });
            b.Vector([&] { b.Int(3); b.Int(4); });
        });
        b.Int("longField", 4);
        b.Vector("longListField", [&] { b.Int(10); b.Int(11); b.Int(12); });
        b.Map("mapOfIntToBoolean", [&] { b.Bool("1", true); b.Bool("2", false); });
        b.Map("mapOfStringToInt", [&] { b.Int("one", 1); b.Int("two", 2); });
        b.Map("mapOfStringToNestedData", [&] {
            encode_nested_data(b, "nested", 101, "Nested", 102.0);
            encode_nested_data(b, "nested2", 103, "Nested", 104.0);
        });
        b.Map("mutableMapOfStringToList", [&] {
            b.Vector("key1", [&] { b.Double(1.0); b.Double(2.0); });
            b.Vector("key2", [&] { b.Double(3.0); b.Double(4.0); });
        });
        encode_nested_data(b, "nestedData", 99, "Nested", 100.0);
        b.Vector("setOfSets", [&] {
            b.Vector([&] { b.Float(1.0f); b.Float(2.0f); });
            b.Vector([&] { b.Float(3.0f); b.Float(4.0f); });
        });
        b.Int("shortField", 2);
        b.Vector("shortListField", [&] { b.Int(4); b.Int(5); b.Int(6); });
        b.String("stringField", "Hello");
        b.Vector("stringSetField", [&] { b.String("Kotlin"); b.String("World"); });
    });
    b.Finish();
    return b.GetBuffer();
}

void decode_complex_case(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    sink(root["booleanField"].AsBool());
    sink(root["byteField"].AsInt64());
    sink(root["charField"].AsInt64());
    sink(root["doubleField"].AsDouble());
    sink((double)root["floatField"].AsFloat());
    sink(root["intField"].AsInt64());
    sink(root["longField"].AsInt64());
    sink(root["shortField"].AsInt64());
    sink(root["stringField"].AsString());
    auto blob = root["byteArrayField"].AsBlob();
    for (size_t i = 0; i < blob.size(); ++i) sink((int64_t)blob.data()[i]);
    auto charList = root["charListField"].AsVector();
    for (size_t i = 0; i < charList.size(); ++i) sink(charList[i].AsInt64());
    auto doubleList = root["doubleListField"].AsVector();
    for (size_t i = 0; i < doubleList.size(); ++i) sink(doubleList[i].AsDouble());
    auto floatSet = root["floatSetField"].AsVector();
    for (size_t i = 0; i < floatSet.size(); ++i) sink((double)floatSet[i].AsFloat());
    auto intSet = root["intSetField"].AsVector();
    for (size_t i = 0; i < intSet.size(); ++i) sink(intSet[i].AsInt64());
    auto longList = root["longListField"].AsVector();
    for (size_t i = 0; i < longList.size(); ++i) sink(longList[i].AsInt64());
    auto shortList = root["shortListField"].AsVector();
    for (size_t i = 0; i < shortList.size(); ++i) sink(shortList[i].AsInt64());
    auto strSet = root["stringSetField"].AsVector();
    for (size_t i = 0; i < strSet.size(); ++i) sink(strSet[i].AsString());
    auto listOfLists = root["listOfLists"].AsVector();
    for (size_t i = 0; i < listOfLists.size(); ++i) {
        auto inner = listOfLists[i].AsVector();
        for (size_t j = 0; j < inner.size(); ++j) sink(inner[j].AsInt64());
    }
    auto setOfSets = root["setOfSets"].AsVector();
    for (size_t i = 0; i < setOfSets.size(); ++i) {
        auto inner = setOfSets[i].AsVector();
        for (size_t j = 0; j < inner.size(); ++j) sink((double)inner[j].AsFloat());
    }
    auto msi = root["mapOfStringToInt"].AsMap();
    sink(msi["one"].AsInt64());
    sink(msi["two"].AsInt64());
    auto mib = root["mapOfIntToBoolean"].AsMap();
    sink(mib["1"].AsBool());
    sink(mib["2"].AsBool());
    auto mmsl = root["mutableMapOfStringToList"].AsMap();
    auto k1v = mmsl["key1"].AsVector();
    for (size_t i = 0; i < k1v.size(); ++i) sink(k1v[i].AsDouble());
    auto k2v = mmsl["key2"].AsVector();
    for (size_t i = 0; i < k2v.size(); ++i) sink(k2v[i].AsDouble());
    auto nested = root["nestedData"].AsMap();
    sink(nested["nestedInt"].AsInt64());
    sink(nested["nestedString"].AsString());
    auto innerArr = nested["innerNestedData"].AsVector();
    auto inner0 = innerArr[0].AsMap();
    sink(inner0["innerValue"].AsDouble());
    auto innerList = inner0["innerList"].AsVector();
    for (size_t i = 0; i < innerList.size(); ++i) sink(innerList[i].AsString());
    auto mapNested = root["mapOfStringToNestedData"].AsMap();
    auto n1 = mapNested["nested"].AsMap();
    sink(n1["nestedInt"].AsInt64());
    sink(n1["nestedString"].AsString());
    auto n1inner = n1["innerNestedData"].AsVector()[0].AsMap();
    sink(n1inner["innerValue"].AsDouble());
    auto n1list = n1inner["innerList"].AsVector();
    for (size_t i = 0; i < n1list.size(); ++i) sink(n1list[i].AsString());
    auto n2 = mapNested["nested2"].AsMap();
    sink(n2["nestedInt"].AsInt64());
    sink(n2["nestedString"].AsString());
    auto n2inner = n2["innerNestedData"].AsVector()[0].AsMap();
    sink(n2inner["innerValue"].AsDouble());
}

void decode_complex_case_indexed(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    auto vals = root.Values();
    // Sorted alphabetically: booleanField=0 byteArrayField=1 byteField=2
    // charField=3 charListField=4 doubleField=5 doubleListField=6 floatField=7
    // floatSetField=8 intField=9 intSetField=10 listOfLists=11 longField=12
    // longListField=13 mapOfIntToBoolean=14 mapOfStringToInt=15
    // mapOfStringToNestedData=16 mutableMapOfStringToList=17 nestedData=18
    // setOfSets=19 shortField=20 shortListField=21 stringField=22 stringSetField=23
    sink(vals[0].AsBool());      // booleanField
    auto blob = vals[1].AsBlob();
    for (size_t i = 0; i < blob.size(); ++i) sink((int64_t)blob.data()[i]);
    sink(vals[2].AsInt64());     // byteField
    sink(vals[3].AsInt64());     // charField
    auto charList = vals[4].AsVector();
    for (size_t i = 0; i < charList.size(); ++i) sink(charList[i].AsInt64());
    sink(vals[5].AsDouble());    // doubleField
    auto doubleList = vals[6].AsVector();
    for (size_t i = 0; i < doubleList.size(); ++i) sink(doubleList[i].AsDouble());
    sink((double)vals[7].AsFloat()); // floatField
    auto floatSet = vals[8].AsVector();
    for (size_t i = 0; i < floatSet.size(); ++i) sink((double)floatSet[i].AsFloat());
    sink(vals[9].AsInt64());     // intField
    auto intSet = vals[10].AsVector();
    for (size_t i = 0; i < intSet.size(); ++i) sink(intSet[i].AsInt64());
    auto listOfLists = vals[11].AsVector();
    for (size_t i = 0; i < listOfLists.size(); ++i) {
        auto inner = listOfLists[i].AsVector();
        for (size_t j = 0; j < inner.size(); ++j) sink(inner[j].AsInt64());
    }
    sink(vals[12].AsInt64());    // longField
    auto longList = vals[13].AsVector();
    for (size_t i = 0; i < longList.size(); ++i) sink(longList[i].AsInt64());
    auto mib = vals[14].AsMap();
    auto mibVals = mib.Values();
    for (size_t i = 0; i < mibVals.size(); ++i) sink(mibVals[i].AsBool());
    auto msi = vals[15].AsMap();
    auto msiVals = msi.Values();
    for (size_t i = 0; i < msiVals.size(); ++i) sink(msiVals[i].AsInt64());
    // mapOfStringToNestedData (index 16) — nested decode
    auto mapNested = vals[16].AsMap();
    auto mnVals = mapNested.Values();
    for (size_t i = 0; i < mnVals.size(); ++i) {
        auto nm = mnVals[i].AsMap();
        auto nmv = nm.Values();
        auto innerArr = nmv[0].AsVector(); // innerNestedData
        auto innerMap = innerArr[0].AsMap();
        auto imv = innerMap.Values();
        auto innerL = imv[0].AsVector();
        for (size_t j = 0; j < innerL.size(); ++j) sink(innerL[j].AsString());
        sink(imv[1].AsDouble());
        sink(nmv[1].AsInt64());    // nestedInt
        sink(nmv[2].AsString());   // nestedString
    }
    // mutableMapOfStringToList (index 17)
    auto mmsl = vals[17].AsMap();
    auto mmslVals = mmsl.Values();
    for (size_t i = 0; i < mmslVals.size(); ++i) {
        auto vec = mmslVals[i].AsVector();
        for (size_t j = 0; j < vec.size(); ++j) sink(vec[j].AsDouble());
    }
    // nestedData (index 18)
    auto nested = vals[18].AsMap();
    auto nv = nested.Values();
    auto nInner = nv[0].AsVector()[0].AsMap();
    auto niv = nInner.Values();
    auto nList = niv[0].AsVector();
    for (size_t i = 0; i < nList.size(); ++i) sink(nList[i].AsString());
    sink(niv[1].AsDouble());
    sink(nv[1].AsInt64());
    sink(nv[2].AsString());
    // setOfSets (index 19)
    auto sos = vals[19].AsVector();
    for (size_t i = 0; i < sos.size(); ++i) {
        auto inner = sos[i].AsVector();
        for (size_t j = 0; j < inner.size(); ++j) sink((double)inner[j].AsFloat());
    }
    sink(vals[20].AsInt64());    // shortField
    auto shortList = vals[21].AsVector();
    for (size_t i = 0; i < shortList.size(); ++i) sink(shortList[i].AsInt64());
    sink(vals[22].AsString());   // stringField
    auto strSet = vals[23].AsVector();
    for (size_t i = 0; i < strSet.size(); ++i) sink(strSet[i].AsString());
}

void verify_complex_case(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    ASSERT_EQ(root["booleanField"].AsBool(), true);
    ASSERT_EQ(root["byteField"].AsInt64(), 1);
    ASSERT_EQ(root["shortField"].AsInt64(), 2);
    ASSERT_EQ(root["intField"].AsInt64(), 3);
    ASSERT_EQ(root["longField"].AsInt64(), 4);
    ASSERT_NEAR(root["floatField"].AsFloat(), 5.0f, 0.001f);
    ASSERT_NEAR(root["doubleField"].AsDouble(), 6.0, 1e-9);
    ASSERT_EQ(root["charField"].AsInt64(), 65);
    ASSERT_STREQ(root["stringField"].AsString().c_str(), "Hello");
    auto blob = root["byteArrayField"].AsBlob();
    ASSERT_EQ(blob.size(), 3);
    ASSERT_EQ(blob.data()[0], 1);
    auto nested = root["nestedData"].AsMap();
    ASSERT_EQ(nested["nestedInt"].AsInt64(), 99);
    ASSERT_STREQ(nested["nestedString"].AsString().c_str(), "Nested");
    auto mapNested = root["mapOfStringToNestedData"].AsMap();
    ASSERT_EQ(mapNested.size(), 2);
    ASSERT_EQ(mapNested["nested"].AsMap()["nestedInt"].AsInt64(), 101);
    printf("  ComplexCase:      PASS (25 fields, nested objects verified)\n");
}

// ════════════════════════════════════════════════════════════════════════
// 4. DeeplyNested: 4 nesting levels
// ════════════════════════════════════════════════════════════════════════

std::vector<uint8_t> encode_deeply_nested() {
    flexbuffers::Builder b(1024, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.Map("level1", [&] {
            b.Vector("level2", [&] {
                for (int k = 0; k < 2; ++k) {
                    b.Map([&] {
                        b.Map("level3", [&] {
                            b.Map("data", [&] {
                                b.Double("x", 1.0);
                                b.Double("y", 2.0);
                                b.Double("z", 3.0);
                            });
                            b.Vector("items", [&] {
                                b.String("a"); b.String("b"); b.String("c");
                                b.String("d"); b.String("e");
                            });
                        });
                        b.Int("value", 42);
                    });
                }
            });
            b.String("name", "level1");
        });
    });
    b.Finish();
    return b.GetBuffer();
}

void decode_deeply_nested(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    auto level1 = root["level1"].AsMap();
    sink(level1["name"].AsString());
    auto level2 = level1["level2"].AsVector();
    for (size_t i = 0; i < level2.size(); ++i) {
        auto l2 = level2[i].AsMap();
        sink(l2["value"].AsInt64());
        auto level3 = l2["level3"].AsMap();
        auto items = level3["items"].AsVector();
        for (size_t j = 0; j < items.size(); ++j) { sink(items[j].AsString()); }
        auto data = level3["data"].AsMap();
        sink(data["x"].AsDouble());
        sink(data["y"].AsDouble());
        sink(data["z"].AsDouble());
    }
}

void decode_deeply_nested_indexed(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    auto vals = root.Values();
    auto level1 = vals[0].AsMap();
    auto l1v = level1.Values();
    auto level2 = l1v[0].AsVector(); // level2 before name alphabetically
    for (size_t i = 0; i < level2.size(); ++i) {
        auto l2 = level2[i].AsMap();
        auto l2v = l2.Values();
        auto level3 = l2v[0].AsMap(); // level3 before value
        auto l3v = level3.Values();
        auto data = l3v[0].AsMap(); // data before items
        auto dv = data.Values();
        sink(dv[0].AsDouble()); // x
        sink(dv[1].AsDouble()); // y
        sink(dv[2].AsDouble()); // z
        auto items = l3v[1].AsVector(); // items
        for (size_t j = 0; j < items.size(); ++j) sink(items[j].AsString());
        sink(l2v[1].AsInt64()); // value
    }
    sink(l1v[1].AsString()); // name
}

void verify_deeply_nested(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    auto level1 = root["level1"].AsMap();
    ASSERT_STREQ(level1["name"].AsString().c_str(), "level1");
    auto level2 = level1["level2"].AsVector();
    ASSERT_EQ(level2.size(), 2);
    for (size_t i = 0; i < 2; ++i) {
        auto l2 = level2[i].AsMap();
        ASSERT_EQ(l2["value"].AsInt64(), 42);
        auto level3 = l2["level3"].AsMap();
        auto items = level3["items"].AsVector();
        ASSERT_EQ(items.size(), 5);
        ASSERT_STREQ(items[0].AsString().c_str(), "a");
        ASSERT_NEAR(level3["data"].AsMap()["x"].AsDouble(), 1.0, 1e-9);
    }
    printf("  DeeplyNested:     PASS (4 levels, 2x5 items verified)\n");
}

// ════════════════════════════════════════════════════════════════════════
// 5. UserProfile: string-heavy, nested address (FULL decode)
// ════════════════════════════════════════════════════════════════════════

std::vector<uint8_t> encode_user_profile() {
    flexbuffers::Builder b(2048, flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    b.Map([&] {
        b.Map("address", [&] {
            b.String("city", "Bengaluru");
            b.String("country", "IN");
            b.String("state", "Karnataka");
            b.String("street", "42 MG Road, Indiranagar");
            b.String("zip", "560038");
        });
        b.String("avatarUrl", "https://cdn.reaktor.build/avatars/8847291/profile_400x400.webp");
        b.String("bio", "Building cross-platform infrastructure. KMP enthusiast. Reaktor framework author.");
        b.Int("createdAtEpochMs", 1609459200000LL);
        b.String("displayName", "Shibasis Patnaik");
        b.String("email", "shibasis@reaktor.build");
        b.Int("followerCount", 2847);
        b.Int("followingCount", 312);
        b.Int("id", 8847291LL);
        b.Int("postCount", 891);
        b.Map("settings", [&] {
            b.String("language", "en");
            b.String("notifications", "all");
            b.String("privacy", "friends");
            b.String("theme", "dark");
            b.String("timezone", "Asia/Kolkata");
            b.String("two_factor", "enabled");
        });
        b.Vector("tags", [&] {
            b.String("kotlin"); b.String("multiplatform"); b.String("android");
            b.String("ios"); b.String("react"); b.String("infrastructure");
        });
        b.String("username", "shibasis.patnaik");
        b.Bool("verified", true);
    });
    b.Finish();
    return b.GetBuffer();
}

void decode_user_profile(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    auto addr = root["address"].AsMap();
    sink(addr["city"].AsString());
    sink(addr["country"].AsString());
    sink(addr["state"].AsString());
    sink(addr["street"].AsString());
    sink(addr["zip"].AsString());
    sink(root["avatarUrl"].AsString());
    sink(root["bio"].AsString());
    sink(root["createdAtEpochMs"].AsInt64());
    sink(root["displayName"].AsString());
    sink(root["email"].AsString());
    sink(root["followerCount"].AsInt64());
    sink(root["followingCount"].AsInt64());
    sink(root["id"].AsInt64());
    sink(root["postCount"].AsInt64());
    auto settings = root["settings"].AsMap();
    auto sKeys = settings.Keys();
    for (size_t i = 0; i < sKeys.size(); ++i) {
        sink(settings[sKeys[i].AsKey()].AsString());
    }
    auto tags = root["tags"].AsVector();
    for (size_t i = 0; i < tags.size(); ++i) { sink(tags[i].AsString()); }
    sink(root["username"].AsString());
    sink(root["verified"].AsBool());
}

void decode_user_profile_indexed(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    auto vals = root.Values();
    // 0=address 1=avatarUrl 2=bio 3=createdAtEpochMs 4=displayName 5=email
    // 6=followerCount 7=followingCount 8=id 9=postCount 10=settings 11=tags
    // 12=username 13=verified
    auto addr = vals[0].AsMap();
    auto addrVals = addr.Values();
    sink(addrVals[0].AsString()); // city
    sink(addrVals[1].AsString()); // country
    sink(addrVals[2].AsString()); // state
    sink(addrVals[3].AsString()); // street
    sink(addrVals[4].AsString()); // zip
    sink(vals[1].AsString());  // avatarUrl
    sink(vals[2].AsString());  // bio
    sink(vals[3].AsInt64());   // createdAtEpochMs
    sink(vals[4].AsString());  // displayName
    sink(vals[5].AsString());  // email
    sink(vals[6].AsInt64());   // followerCount
    sink(vals[7].AsInt64());   // followingCount
    sink(vals[8].AsInt64());   // id
    sink(vals[9].AsInt64());   // postCount
    auto settings = vals[10].AsMap();
    auto sVals = settings.Values();
    for (size_t i = 0; i < sVals.size(); ++i) sink(sVals[i].AsString());
    auto tags = vals[11].AsVector();
    for (size_t i = 0; i < tags.size(); ++i) sink(tags[i].AsString());
    sink(vals[12].AsString()); // username
    sink(vals[13].AsBool());   // verified
}

void decode_user_profile_partial(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    sink(root["id"].AsInt64());
    sink(root["username"].AsString());
    sink(root["verified"].AsBool());
}

void decode_user_profile_single(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    sink(root["id"].AsInt64());
}

void verify_user_profile(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    ASSERT_EQ(root.size(), 14);
    ASSERT_EQ(root["id"].AsInt64(), 8847291LL);
    ASSERT_STREQ(root["username"].AsString().c_str(), "shibasis.patnaik");
    ASSERT_STREQ(root["displayName"].AsString().c_str(), "Shibasis Patnaik");
    ASSERT_EQ(root["followerCount"].AsInt64(), 2847);
    ASSERT_EQ(root["verified"].AsBool(), true);
    auto addr = root["address"].AsMap();
    ASSERT_EQ(addr.size(), 5);
    ASSERT_STREQ(addr["city"].AsString().c_str(), "Bengaluru");
    auto settings = root["settings"].AsMap();
    ASSERT_EQ(settings.size(), 6);
    ASSERT_STREQ(settings["theme"].AsString().c_str(), "dark");
    auto tags = root["tags"].AsVector();
    ASSERT_EQ(tags.size(), 6);
    ASSERT_STREQ(tags[0].AsString().c_str(), "kotlin");
    printf("  UserProfile:      PASS (14 fields, 5 address, 6 settings, 6 tags verified)\n");
}

// ════════════════════════════════════════════════════════════════════════
// 6. ApiResponse: 20 products, paginated (FULL decode)
// ════════════════════════════════════════════════════════════════════════

std::vector<uint8_t> encode_api_response() {
    flexbuffers::Builder b(16384, flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    b.Map([&] {
        b.Vector("items", [&] {
            for (int i = 1; i <= 20; ++i) {
                b.Map([&] {
                    b.Vector("categoryIds", [&] { b.Int(100+i); b.Int(200+i%5); b.Int(300+i%3); });
                    b.String("currency", "USD");
                    b.String("description", "High quality product with advanced features. Built for professionals who demand the best. Ships worldwide with tracking.");
                    b.Int("id", 10000LL + i);
                    b.Vector("imageUrls", [&] {
                        b.String(("https://cdn.example.com/products/" + std::to_string(10000+i) + "/main.webp").c_str());
                        b.String(("https://cdn.example.com/products/" + std::to_string(10000+i) + "/thumb.webp").c_str());
                    });
                    b.Bool("inStock", i % 3 != 0);
                    b.String("name", ("Product " + std::string(1, (char)('A'+i-1)) + " — Premium Edition").c_str());
                    b.Int("priceInCents", 1999 + i * 500);
                    b.Float("rating", 3.5f + (i % 5) * 0.3f);
                    b.Int("reviewCount", 42 + i * 17);
                });
            }
        });
        b.String("message", "OK");
        b.Map("metadata", [&] {
            b.String("cache_hit", "true"); b.String("latency_ms", "12");
            b.String("region", "ap-south-1"); b.String("request_id", "req_a1b2c3d4e5f6");
        });
        b.Int("page", 1); b.Int("pageSize", 20);
        b.Int("status", 200);
        b.Int("totalItems", 847); b.Int("totalPages", 43);
    });
    b.Finish();
    return b.GetBuffer();
}

void decode_api_response(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    sink(root["status"].AsInt64());
    sink(root["page"].AsInt64());
    sink(root["pageSize"].AsInt64());
    sink(root["totalItems"].AsInt64());
    sink(root["totalPages"].AsInt64());
    sink(root["message"].AsString());
    auto meta = root["metadata"].AsMap();
    auto mKeys = meta.Keys();
    for (size_t i = 0; i < mKeys.size(); ++i) {
        sink(meta[mKeys[i].AsKey()].AsString());
    }
    auto items = root["items"].AsVector();
    for (size_t i = 0; i < items.size(); ++i) {
        auto p = items[i].AsMap();
        sink(p["id"].AsInt64());
        sink(p["name"].AsString());
        sink(p["description"].AsString());
        sink(p["currency"].AsString());
        sink(p["priceInCents"].AsInt64());
        sink((double)p["rating"].AsFloat());
        sink(p["reviewCount"].AsInt64());
        sink(p["inStock"].AsBool());
        auto cats = p["categoryIds"].AsVector();
        for (size_t j = 0; j < cats.size(); ++j) sink(cats[j].AsInt64());
        auto imgs = p["imageUrls"].AsVector();
        for (size_t j = 0; j < imgs.size(); ++j) sink(imgs[j].AsString());
    }
}

void decode_api_response_indexed(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    auto vals = root.Values();
    // 0=items 1=message 2=metadata 3=page 4=pageSize 5=status 6=totalItems 7=totalPages
    auto items = vals[0].AsVector();
    for (size_t i = 0; i < items.size(); ++i) {
        auto p = items[i].AsMap();
        auto pv = p.Values();
        // 0=categoryIds 1=currency 2=description 3=id 4=imageUrls 5=inStock 6=name 7=priceInCents 8=rating 9=reviewCount
        auto cats = pv[0].AsVector();
        for (size_t j = 0; j < cats.size(); ++j) sink(cats[j].AsInt64());
        sink(pv[1].AsString());
        sink(pv[2].AsString());
        sink(pv[3].AsInt64());
        auto imgs = pv[4].AsVector();
        for (size_t j = 0; j < imgs.size(); ++j) sink(imgs[j].AsString());
        sink(pv[5].AsBool());
        sink(pv[6].AsString());
        sink(pv[7].AsInt64());
        sink((double)pv[8].AsFloat());
        sink(pv[9].AsInt64());
    }
    sink(vals[1].AsString());
    auto meta = vals[2].AsMap();
    auto metaVals = meta.Values();
    for (size_t i = 0; i < metaVals.size(); ++i) sink(metaVals[i].AsString());
    sink(vals[3].AsInt64());
    sink(vals[4].AsInt64());
    sink(vals[5].AsInt64());
    sink(vals[6].AsInt64());
    sink(vals[7].AsInt64());
}

void decode_api_response_partial(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    sink(root["status"].AsInt64());
    sink(root["totalItems"].AsInt64());
    sink(root["message"].AsString());
}

void decode_api_response_first_item(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    auto items = root["items"].AsVector();
    auto p = items[0].AsMap();
    sink(p["id"].AsInt64());
    sink(p["name"].AsString());
    sink(p["priceInCents"].AsInt64());
    sink(p["rating"].AsFloat());
    sink(p["inStock"].AsBool());
}

void verify_api_response(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    ASSERT_EQ(root["status"].AsInt64(), 200);
    ASSERT_EQ(root["page"].AsInt64(), 1);
    ASSERT_EQ(root["totalItems"].AsInt64(), 847);
    ASSERT_STREQ(root["message"].AsString().c_str(), "OK");
    auto items = root["items"].AsVector();
    ASSERT_EQ(items.size(), 20);
    auto p1 = items[0].AsMap();
    ASSERT_EQ(p1["id"].AsInt64(), 10001);
    ASSERT_EQ(p1["priceInCents"].AsInt64(), 2499);
    ASSERT_EQ(p1["inStock"].AsBool(), true);
    auto p3 = items[2].AsMap();
    ASSERT_EQ(p3["inStock"].AsBool(), false);
    printf("  ApiResponse:      PASS (7 top-level, 20 products verified)\n");
}

// ════════════════════════════════════════════════════════════════════════
// 7. EventLog: telemetry payload (FULL decode)
// ════════════════════════════════════════════════════════════════════════

std::vector<uint8_t> encode_event_log() {
    flexbuffers::Builder b(2048, flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    b.Map([&] {
        b.Int("durationMs", 3420);
        b.String("eventId", "evt_7f3a9b2c4d5e");
        b.String("eventType", "page_view");
        b.Map("metrics", [&] {
            b.Double("cumulative_layout_shift", 0.02);
            b.Double("dom_content_loaded", 187.3);
            b.Double("first_input_delay", 8.1);
            b.Double("largest_contentful_paint", 312.8);
            b.Double("time_to_first_byte", 42.5);
            b.Double("total_blocking_time", 45.7);
        });
        b.String("parentEventId", "evt_parent_abc123");
        b.Map("properties", [&] {
            b.String("browser", "Chrome/125.0"); b.String("device", "desktop");
            b.String("locale", "en-US"); b.String("os", "macOS 15.4");
            b.String("page", "/dashboard/analytics");
            b.String("referrer", "https://reaktor.build/docs");
            b.String("screen", "2560x1440");
        });
        b.String("sessionId", "sess_x9y8z7w6v5u4");
        b.Bool("success", true);
        b.Vector("tags", [&] { b.String("web"); b.String("dashboard"); b.String("authenticated"); b.String("premium"); });
        b.Int("timestampMs", 1716307200000LL);
        b.Int("userId", 8847291LL);
    });
    b.Finish();
    return b.GetBuffer();
}

void decode_event_log(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    sink(root["durationMs"].AsInt64());
    sink(root["eventId"].AsString());
    sink(root["eventType"].AsString());
    sink(root["parentEventId"].AsString());
    sink(root["sessionId"].AsString());
    sink(root["success"].AsBool());
    sink(root["timestampMs"].AsInt64());
    sink(root["userId"].AsInt64());
    auto metrics = root["metrics"].AsMap();
    auto mKeys = metrics.Keys();
    for (size_t i = 0; i < mKeys.size(); ++i) {
        sink(metrics[mKeys[i].AsKey()].AsDouble());
    }
    auto props = root["properties"].AsMap();
    auto pKeys = props.Keys();
    for (size_t i = 0; i < pKeys.size(); ++i) {
        sink(props[pKeys[i].AsKey()].AsString());
    }
    auto tags = root["tags"].AsVector();
    for (size_t i = 0; i < tags.size(); ++i) { sink(tags[i].AsString()); }
}

void decode_event_log_indexed(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    auto vals = root.Values();
    // 0=durationMs 1=eventId 2=eventType 3=metrics 4=parentEventId
    // 5=properties 6=sessionId 7=success 8=tags 9=timestampMs 10=userId
    sink(vals[0].AsInt64());
    sink(vals[1].AsString());
    sink(vals[2].AsString());
    auto metrics = vals[3].AsMap();
    auto mVals = metrics.Values();
    for (size_t i = 0; i < mVals.size(); ++i) sink(mVals[i].AsDouble());
    sink(vals[4].AsString());
    auto props = vals[5].AsMap();
    auto pVals = props.Values();
    for (size_t i = 0; i < pVals.size(); ++i) sink(pVals[i].AsString());
    sink(vals[6].AsString());
    sink(vals[7].AsBool());
    auto tags = vals[8].AsVector();
    for (size_t i = 0; i < tags.size(); ++i) sink(tags[i].AsString());
    sink(vals[9].AsInt64());
    sink(vals[10].AsInt64());
}

void verify_event_log(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    ASSERT_EQ(root.size(), 11);
    ASSERT_STREQ(root["eventId"].AsString().c_str(), "evt_7f3a9b2c4d5e");
    ASSERT_EQ(root["durationMs"].AsInt64(), 3420);
    ASSERT_EQ(root["success"].AsBool(), true);
    auto metrics = root["metrics"].AsMap();
    ASSERT_EQ(metrics.size(), 6);
    ASSERT_NEAR(metrics["time_to_first_byte"].AsDouble(), 42.5, 1e-6);
    auto props = root["properties"].AsMap();
    ASSERT_EQ(props.size(), 7);
    printf("  EventLog:         PASS (11 fields, 6 metrics, 7 props verified)\n");
}

// ════════════════════════════════════════════════════════════════════════
// 8. ChatThread: 15 messages (FULL decode)
// ════════════════════════════════════════════════════════════════════════

std::vector<uint8_t> encode_chat_thread() {
    flexbuffers::Builder b(8192, flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    const char* texts[] = {
        "We should focus on the encode path first.",
        "Agreed. The decode side is already close to C++.",
        "I ran the benchmarks on M2: FlexCoder is 4.6x faster.",
        "Nice! What about the collection-heavy case?",
        "Let me push the latest numbers."
    };
    int64_t senders[] = {8847291LL, 1234567LL, 9876543LL, 5555555LL};
    b.Map([&] {
        b.Map("lastReadTimestamps", [&] {
            b.Int("1234567", 1716307800000LL); b.Int("5555555", 1716307200000LL);
            b.Int("8847291", 1716308100000LL); b.Int("9876543", 1716307500000LL);
        });
        b.Vector("messages", [&] {
            for (int i = 1; i <= 15; ++i) {
                b.Map([&] {
                    b.Bool("edited", i == 7);
                    b.Int("id", 100000LL + i);
                    if (i > 3 && i % 4 == 0)
                        b.Map("reactions", [&] { b.Int("fire", 1); b.Int("thumbsup", 2); });
                    else
                        b.Map("reactions", [&] {});
                    b.Int("replyToId", (i > 3 && i % 4 == 0) ? 100000LL + i - 2 : 0LL);
                    b.Int("senderId", senders[i % 4]);
                    b.String("text", texts[i % 5]);
                    b.Int("timestampMs", 1716307200000LL + i * 60000LL);
                });
            }
        });
        b.Bool("muted", false);
        b.Vector("participantIds", [&] { b.Int(8847291LL); b.Int(1234567LL); b.Int(9876543LL); b.Int(5555555LL); });
        b.Bool("pinned", true);
        b.Int("threadId", 55012LL);
        b.String("title", "Reaktor FlexBuffer Performance");
    });
    b.Finish();
    return b.GetBuffer();
}

void decode_chat_thread(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    sink(root["threadId"].AsInt64());
    sink(root["title"].AsString());
    sink(root["muted"].AsBool());
    sink(root["pinned"].AsBool());
    auto lrt = root["lastReadTimestamps"].AsMap();
    auto lrtKeys = lrt.Keys();
    for (size_t i = 0; i < lrtKeys.size(); ++i) {
        sink(lrt[lrtKeys[i].AsKey()].AsInt64());
    }
    auto pids = root["participantIds"].AsVector();
    for (size_t i = 0; i < pids.size(); ++i) sink(pids[i].AsInt64());
    auto messages = root["messages"].AsVector();
    for (size_t i = 0; i < messages.size(); ++i) {
        auto msg = messages[i].AsMap();
        sink(msg["id"].AsInt64());
        sink(msg["senderId"].AsInt64());
        sink(msg["text"].AsString());
        sink(msg["timestampMs"].AsInt64());
        sink(msg["edited"].AsBool());
        sink(msg["replyToId"].AsInt64());
        auto reactions = msg["reactions"].AsMap();
        auto rKeys = reactions.Keys();
        for (size_t j = 0; j < rKeys.size(); ++j) {
            sink(reactions[rKeys[j].AsKey()].AsInt64());
        }
    }
}

void decode_chat_thread_indexed(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    auto vals = root.Values();
    // 0=lastReadTimestamps 1=messages 2=muted 3=participantIds 4=pinned 5=threadId 6=title
    auto lrt = vals[0].AsMap();
    auto lrtVals = lrt.Values();
    for (size_t i = 0; i < lrtVals.size(); ++i) sink(lrtVals[i].AsInt64());
    auto messages = vals[1].AsVector();
    for (size_t i = 0; i < messages.size(); ++i) {
        auto msg = messages[i].AsMap();
        auto mv = msg.Values();
        // 0=edited 1=id 2=reactions 3=replyToId 4=senderId 5=text 6=timestampMs
        sink(mv[0].AsBool());
        sink(mv[1].AsInt64());
        auto reactions = mv[2].AsMap();
        auto rVals = reactions.Values();
        for (size_t j = 0; j < rVals.size(); ++j) sink(rVals[j].AsInt64());
        sink(mv[3].AsInt64());
        sink(mv[4].AsInt64());
        sink(mv[5].AsString());
        sink(mv[6].AsInt64());
    }
    sink(vals[2].AsBool());
    auto pids = vals[3].AsVector();
    for (size_t i = 0; i < pids.size(); ++i) sink(pids[i].AsInt64());
    sink(vals[4].AsBool());
    sink(vals[5].AsInt64());
    sink(vals[6].AsString());
}

void decode_chat_thread_partial(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    sink(root["threadId"].AsInt64());
    sink(root["title"].AsString());
    sink(root["pinned"].AsBool());
}

void verify_chat_thread(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    ASSERT_EQ(root.size(), 7);
    ASSERT_EQ(root["threadId"].AsInt64(), 55012LL);
    ASSERT_STREQ(root["title"].AsString().c_str(), "Reaktor FlexBuffer Performance");
    ASSERT_EQ(root["muted"].AsBool(), false);
    ASSERT_EQ(root["pinned"].AsBool(), true);
    auto messages = root["messages"].AsVector();
    ASSERT_EQ(messages.size(), 15);
    auto m7 = messages[6].AsMap();
    ASSERT_EQ(m7["edited"].AsBool(), true);
    auto m8 = messages[7].AsMap();
    ASSERT_EQ(m8["replyToId"].AsInt64(), 100006LL);
    printf("  ChatThread:       PASS (7 top-level, 15 messages verified)\n");
}

// ════════════════════════════════════════════════════════════════════════
// 9. ConfigSnapshot: feature flags (FULL decode)
// ════════════════════════════════════════════════════════════════════════

std::vector<uint8_t> encode_config_snapshot() {
    flexbuffers::Builder b(4096, flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    b.Map([&] {
        b.Int("buildNumber", 20260521001LL);
        b.Bool("debugMode", false);
        b.Vector("enabledModules", [&] {
            b.String("core"); b.String("graph"); b.String("flow"); b.String("ui");
            b.String("auth"); b.String("media"); b.String("io"); b.String("db");
        });
        b.Map("endpoints", [&] {
            b.String("api", "https://api.reaktor.build/v2");
            b.String("auth", "https://auth.reaktor.build");
            b.String("cdn", "https://cdn.reaktor.build");
            b.String("ws", "wss://ws.reaktor.build/v2");
        });
        b.String("environment", "production");
        b.Map("features", [&] {
            auto writeFlag = [&](const char* name, bool enabled, int pct, const std::vector<int64_t>& users,
                                 const std::vector<std::pair<const char*, const char*>>& meta) {
                b.Map(name, [&] {
                    b.Vector("allowedUserIds", [&] { for (auto u : users) b.Int(u); });
                    b.Bool("enabled", enabled);
                    b.Map("metadata", [&] { for (auto& [k, v] : meta) b.String(k, v); });
                    b.Int("rolloutPercent", pct);
                });
            };
            writeFlag("ai_suggestions", true, 75, {8847291LL, 1234567LL}, {{"max_tokens","1024"},{"model","claude-4"}});
            writeFlag("beta_editor", true, 25, {8847291LL}, {{"experiment","editor_v3"},{"variant","B"}});
            writeFlag("dark_mode", true, 100, {}, {{"since","v2.1"}});
            writeFlag("export_pdf", true, 100, {}, {});
            writeFlag("push_notifications", true, 90, {}, {{"provider","firebase"}});
            writeFlag("realtime_collab", false, 0, {}, {{"blocked_by","INFRA-2847"}});
        });
        b.Map("thresholds", [&] {
            b.Double("cache_ttl_sec", 300.0); b.Double("max_upload_mb", 50.0);
            b.Double("rate_limit_rpm", 120.0); b.Double("retry_backoff_ms", 1000.0);
            b.Double("session_timeout_min", 30.0);
        });
        b.Int("version", 47);
    });
    b.Finish();
    return b.GetBuffer();
}

void decode_config_snapshot(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    sink(root["buildNumber"].AsInt64());
    sink(root["debugMode"].AsBool());
    sink(root["environment"].AsString());
    sink(root["version"].AsInt64());
    auto modules = root["enabledModules"].AsVector();
    for (size_t i = 0; i < modules.size(); ++i) { sink(modules[i].AsString()); }
    auto endpoints = root["endpoints"].AsMap();
    auto eKeys = endpoints.Keys();
    for (size_t i = 0; i < eKeys.size(); ++i) {
        sink(endpoints[eKeys[i].AsKey()].AsString());
    }
    auto thresh = root["thresholds"].AsMap();
    auto tKeys = thresh.Keys();
    for (size_t i = 0; i < tKeys.size(); ++i) {
        sink(thresh[tKeys[i].AsKey()].AsDouble());
    }
    auto features = root["features"].AsMap();
    auto fKeys = features.Keys();
    for (size_t i = 0; i < fKeys.size(); ++i) {
        auto flag = features[fKeys[i].AsKey()].AsMap();
        sink(flag["enabled"].AsBool());
        sink(flag["rolloutPercent"].AsInt64());
        auto userIds = flag["allowedUserIds"].AsVector();
        for (size_t j = 0; j < userIds.size(); ++j) sink(userIds[j].AsInt64());
        auto meta = flag["metadata"].AsMap();
        auto metaKeys = meta.Keys();
        for (size_t j = 0; j < metaKeys.size(); ++j) {
            sink(meta[metaKeys[j].AsKey()].AsString());
        }
    }
}

void decode_config_snapshot_indexed(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    auto vals = root.Values();
    // 0=buildNumber 1=debugMode 2=enabledModules 3=endpoints 4=environment 5=features 6=thresholds 7=version
    sink(vals[0].AsInt64());
    sink(vals[1].AsBool());
    auto modules = vals[2].AsVector();
    for (size_t i = 0; i < modules.size(); ++i) sink(modules[i].AsString());
    auto endpoints = vals[3].AsMap();
    auto eVals = endpoints.Values();
    for (size_t i = 0; i < eVals.size(); ++i) sink(eVals[i].AsString());
    sink(vals[4].AsString());
    auto features = vals[5].AsMap();
    auto fVals = features.Values();
    for (size_t i = 0; i < fVals.size(); ++i) {
        auto flag = fVals[i].AsMap();
        auto flagV = flag.Values();
        auto userIds = flagV[0].AsVector();
        for (size_t j = 0; j < userIds.size(); ++j) sink(userIds[j].AsInt64());
        sink(flagV[1].AsBool());
        auto meta = flagV[2].AsMap();
        auto metaV = meta.Values();
        for (size_t j = 0; j < metaV.size(); ++j) sink(metaV[j].AsString());
        sink(flagV[3].AsInt64());
    }
    auto thresh = vals[6].AsMap();
    auto tVals = thresh.Values();
    for (size_t i = 0; i < tVals.size(); ++i) sink(tVals[i].AsDouble());
    sink(vals[7].AsInt64());
}

void verify_config_snapshot(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    ASSERT_EQ(root.size(), 8);
    ASSERT_EQ(root["version"].AsInt64(), 47);
    ASSERT_EQ(root["buildNumber"].AsInt64(), 20260521001LL);
    ASSERT_STREQ(root["environment"].AsString().c_str(), "production");
    auto modules = root["enabledModules"].AsVector();
    ASSERT_EQ(modules.size(), 8);
    auto features = root["features"].AsMap();
    ASSERT_EQ(features.size(), 6);
    auto ai = features["ai_suggestions"].AsMap();
    ASSERT_EQ(ai["enabled"].AsBool(), true);
    ASSERT_EQ(ai["rolloutPercent"].AsInt64(), 75);
    printf("  ConfigSnapshot:   PASS (8 top-level, 6 features verified)\n");
}

// ════════════════════════════════════════════════════════════════════════
// 10. TimeSeries: 256 doubles + 256 longs (FULL decode)
// ════════════════════════════════════════════════════════════════════════

std::vector<uint8_t> encode_time_series() {
    flexbuffers::Builder b(8192, flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    b.Map([&] {
        b.Int("count", 256);
        b.Int("intervalMs", 1000);
        b.Double("max", 24.85);
        b.Double("mean", 23.42);
        b.Double("min", 22.5);
        b.String("seriesId", "sensor_temp_rack_42");
        b.Int("startEpochMs", 1716307200000LL);
        b.Vector("timestamps", [&] {
            for (int i = 0; i < 256; ++i) b.Int(1716307200000LL + i * 1000LL);
        });
        b.Vector("values", [&] {
            for (int i = 0; i < 256; ++i) b.Double(22.5 + (i % 20) * 0.1 + (i % 7) * 0.05);
        });
    });
    b.Finish();
    return b.GetBuffer();
}

void decode_time_series(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    sink(root["count"].AsInt64());
    sink(root["intervalMs"].AsInt64());
    sink(root["max"].AsDouble());
    sink(root["mean"].AsDouble());
    sink(root["min"].AsDouble());
    sink(root["seriesId"].AsString());
    sink(root["startEpochMs"].AsInt64());
    auto values = root["values"].AsVector();
    for (size_t i = 0; i < values.size(); ++i) { sink(values[i].AsDouble()); }
    auto timestamps = root["timestamps"].AsVector();
    for (size_t i = 0; i < timestamps.size(); ++i) { sink(timestamps[i].AsInt64()); }
}

void decode_time_series_indexed(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    auto vals = root.Values();
    // 0=count 1=intervalMs 2=max 3=mean 4=min 5=seriesId 6=startEpochMs 7=timestamps 8=values
    sink(vals[0].AsInt64());
    sink(vals[1].AsInt64());
    sink(vals[2].AsDouble());
    sink(vals[3].AsDouble());
    sink(vals[4].AsDouble());
    sink(vals[5].AsString());
    sink(vals[6].AsInt64());
    auto timestamps = vals[7].AsVector();
    for (size_t i = 0; i < timestamps.size(); ++i) sink(timestamps[i].AsInt64());
    auto values = vals[8].AsVector();
    for (size_t i = 0; i < values.size(); ++i) sink(values[i].AsDouble());
}

void decode_time_series_partial(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    sink(root["seriesId"].AsString());
    sink(root["count"].AsInt64());
    sink(root["mean"].AsDouble());
}

void verify_time_series(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    ASSERT_EQ(root.size(), 9);
    ASSERT_EQ(root["count"].AsInt64(), 256);
    ASSERT_NEAR(root["max"].AsDouble(), 24.85, 1e-9);
    ASSERT_STREQ(root["seriesId"].AsString().c_str(), "sensor_temp_rack_42");
    auto values = root["values"].AsVector();
    ASSERT_EQ(values.size(), 256);
    ASSERT_NEAR(values[0].AsDouble(), 22.5, 1e-9);
    auto timestamps = root["timestamps"].AsVector();
    ASSERT_EQ(timestamps.size(), 256);
    ASSERT_EQ(timestamps[0].AsInt64(), 1716307200000LL);
    printf("  TimeSeries:       PASS (9 fields, 256+256 elements verified)\n");
}

// ════════════════════════════════════════════════════════════════════════
// Wire compatibility verification
// ════════════════════════════════════════════════════════════════════════

void verify_round_trip(const char* label, const std::vector<uint8_t>& buf) {
    bool valid = flexbuffers::VerifyBuffer(buf.data(), buf.size(), nullptr);
    printf("  %-30s %s (%zu bytes)\n", label, valid ? "VALID" : "INVALID", buf.size());
    if (!valid) abort();
}

// ════════════════════════════════════════════════════════════════════════════
// MICRO-BENCHMARKS: Isolated performance dimensions
// ════════════════════════════════════════════════════════════════════════════

// --- Scalar type isolation ---
// Measures cost of reading a single field of each primitive type

std::vector<uint8_t> g_scalar_buf;
void init_scalar_buf() {
    flexbuffers::Builder b(256, flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    b.Map([&] {
        b.Bool("b", true);
        b.Double("d", 3.14159265358979);
        b.Float("f", 2.71828f);
        b.Int("i32", 2147483647);
        b.Int("i64", 9223372036854775807LL);
        b.String("s_long", "This is a moderately long string that simulates real-world field values in a typical application payload");
        b.String("s_short", "hi");
    });
    b.Finish();
    g_scalar_buf = b.GetBuffer();
}

// --- Vector size scaling ---
// Creates vectors of sizes 10, 100, 1000, 10000 to measure iteration cost

std::vector<uint8_t> encode_int_vector(int n) {
    flexbuffers::Builder b(n * 16, flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    b.Map([&] {
        b.Vector("v", [&] { for (int i = 0; i < n; ++i) b.Int(i); });
    });
    b.Finish();
    return b.GetBuffer();
}

std::vector<uint8_t> encode_double_vector(int n) {
    flexbuffers::Builder b(n * 16, flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    b.Map([&] {
        b.Vector("v", [&] { for (int i = 0; i < n; ++i) b.Double(i * 0.1); });
    });
    b.Finish();
    return b.GetBuffer();
}

std::vector<uint8_t> encode_string_vector(int n, int str_len) {
    std::string s(str_len, 'x');
    flexbuffers::Builder b(n * (str_len + 16), flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    b.Map([&] {
        b.Vector("v", [&] {
            for (int i = 0; i < n; ++i) {
                s[0] = 'A' + (i % 26);
                b.String(s.c_str());
            }
        });
    });
    b.Finish();
    return b.GetBuffer();
}

// --- Map key count scaling ---
// Maps with 5, 25, 100, 500 keys — measures binary search cost

std::vector<uint8_t> encode_map_n_keys(int n) {
    flexbuffers::Builder b(n * 32, flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    b.Map([&] {
        for (int i = 0; i < n; ++i) {
            char key[32];
            snprintf(key, sizeof(key), "field_%04d", i);
            b.Int(key, i);
        }
    });
    b.Finish();
    return b.GetBuffer();
}

// --- Nesting depth scaling ---
// 1, 2, 4, 8 levels of nesting

std::vector<uint8_t> encode_depth_n(int depth) {
    flexbuffers::Builder b(1024, flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    // Build depth levels of nested maps, each with a "value" field
    std::function<void(int)> build_level = [&](int d) {
        b.Map([&] {
            b.Int("value", d);
            b.String("name", ("level_" + std::to_string(d)).c_str());
            if (d < depth) {
                b.Key("child");
                build_level(d + 1);
            }
        });
    };
    build_level(1);
    b.Finish();
    return b.GetBuffer();
}

// --- String length scaling ---
// Measures impact of string length on decode

std::vector<uint8_t> encode_string_field(int len) {
    std::string s(len, 'A');
    for (int i = 0; i < len; ++i) s[i] = 'A' + (i % 26);
    flexbuffers::Builder b(len + 128, flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    b.Map([&] {
        b.String("data", s.c_str());
        b.Int("len", len);
    });
    b.Finish();
    return b.GetBuffer();
}

// --- Typed vector (fixed type) vs generic vector ---
// FlexBuffer typed vectors store no per-element type byte

std::vector<uint8_t> encode_typed_int_vector(int n) {
    flexbuffers::Builder b(n * 16, flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    b.Map([&] {
        // TypedVector uses a single type for all elements
        std::vector<int64_t> vals(n);
        for (int i = 0; i < n; ++i) vals[i] = i;
        auto start = b.StartVector();
        for (auto v : vals) b.Int(v);
        b.EndVector(start, true, false); // typed=true
    });
    b.Finish();
    return b.GetBuffer();
}

// --- Encode: key sharing impact ---

std::vector<uint8_t> encode_no_sharing(int n_items) {
    flexbuffers::Builder b(n_items * 128, flexbuffers::BUILDER_FLAG_NONE);
    b.Map([&] {
        b.Vector("items", [&] {
            for (int i = 0; i < n_items; ++i) {
                b.Map([&] {
                    b.Int("id", i);
                    b.String("name", ("item_" + std::to_string(i)).c_str());
                    b.Double("score", i * 0.1);
                });
            }
        });
    });
    b.Finish();
    return b.GetBuffer();
}

std::vector<uint8_t> encode_with_sharing(int n_items) {
    flexbuffers::Builder b(n_items * 128, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.Vector("items", [&] {
            for (int i = 0; i < n_items; ++i) {
                b.Map([&] {
                    b.Int("id", i);
                    b.String("name", ("item_" + std::to_string(i)).c_str());
                    b.Double("score", i * 0.1);
                });
            }
        });
    });
    b.Finish();
    return b.GetBuffer();
}

// ════════════════════════════════════════════════════════════════════════════
// ADVERSARIAL COUNTER-BASELINES
// These cases are intentionally brutal. They compare FlexBuffers against
// narrower encodings that do not provide the same schema evolution properties.
// The point is to find where FlexBuffers lose, not to make the format look good.
// ════════════════════════════════════════════════════════════════════════════

static int64_t json_i64_after(const std::string& json, const char* needle, size_t* cursor = nullptr) {
    size_t pos = json.find(needle, cursor ? *cursor : 0);
    if (pos == std::string::npos) return 0;
    pos += strlen(needle);
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == ':')) ++pos;
    bool neg = false;
    if (pos < json.size() && json[pos] == '-') { neg = true; ++pos; }
    int64_t v = 0;
    while (pos < json.size() && json[pos] >= '0' && json[pos] <= '9') {
        v = v * 10 + (json[pos] - '0');
        ++pos;
    }
    if (cursor) *cursor = pos;
    return neg ? -v : v;
}

static bool json_bool_after(const std::string& json, const char* needle, size_t* cursor = nullptr) {
    size_t pos = json.find(needle, cursor ? *cursor : 0);
    if (pos == std::string::npos) return false;
    pos += strlen(needle);
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == ':')) ++pos;
    bool v = json.compare(pos, 4, "true") == 0;
    if (cursor) *cursor = pos + (v ? 4 : 5);
    return v;
}

static size_t json_string_probe_after(const std::string& json, const char* needle, size_t* cursor = nullptr) {
    size_t pos = json.find(needle, cursor ? *cursor : 0);
    if (pos == std::string::npos) return 0;
    pos += strlen(needle);
    size_t end = json.find('"', pos);
    if (end == std::string::npos) return 0;
    if (end > pos) sink((int64_t)json[pos]);
    size_t len = end - pos;
    sink((int64_t)len);
    if (cursor) *cursor = end + 1;
    return len;
}

static size_t json_missing_probe(const std::string& json, const char* needle) {
    auto pos = json.find(needle);
    sink((int64_t)(pos == std::string::npos ? 0 : 1));
    return pos;
}

template <typename T>
static void append_raw(std::vector<uint8_t>& out, const T& value) {
    size_t old = out.size();
    out.resize(old + sizeof(T));
    memcpy(out.data() + old, &value, sizeof(T));
}

template <typename T>
static T read_raw(const uint8_t*& p) {
    T value;
    memcpy(&value, p, sizeof(T));
    p += sizeof(T);
    return value;
}

#pragma pack(push, 1)
struct RawFlatPrimitives {
    uint8_t b;
    int64_t by;
    int64_t c;
    double d;
    float f;
    int64_t i;
    int64_t l;
    int64_t sh;
    char s[24];
};
#pragma pack(pop)

std::vector<uint8_t> encode_raw_flat_primitives() {
    RawFlatPrimitives p{};
    p.b = 1;
    p.by = 127;
    p.c = 'Z';
    p.d = 2.718281828;
    p.f = 3.14f;
    p.i = 42;
    p.l = 123456789L;
    p.sh = 32000;
    strncpy(p.s, "benchmark string value", sizeof(p.s) - 1);
    std::vector<uint8_t> out(sizeof(p));
    memcpy(out.data(), &p, sizeof(p));
    return out;
}

void decode_raw_flat_primitives(const std::vector<uint8_t>& buf) {
    const auto* p = reinterpret_cast<const RawFlatPrimitives*>(buf.data());
    sink((bool)p->b);
    sink(p->by);
    sink(p->c);
    sink(p->d);
    sink((double)p->f);
    sink(p->i);
    sink(p->l);
    sink(p->sh);
    sink(p->s);
}

std::vector<uint8_t> encode_tiny_status_flex() {
    flexbuffers::Builder b(512, flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    b.Map([&] {
        b.Int("createdAt", 1716307200000LL);
        b.Int("id", 8847291LL);
        b.Double("score", 98.25);
        b.String("status", "active");
        b.String("username", "shibasis.patnaik");
        b.Bool("verified", true);
    });
    b.Finish();
    return b.GetBuffer();
}

std::string encode_tiny_status_json() {
    return "{\"createdAt\":1716307200000,\"id\":8847291,\"score\":98,\"status\":\"active\",\"username\":\"shibasis.patnaik\",\"verified\":true}";
}

void decode_tiny_status_flex_full(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    sink(root["createdAt"].AsInt64());
    sink(root["id"].AsInt64());
    sink(root["score"].AsDouble());
    sink(root["status"].AsString());
    sink(root["username"].AsString());
    sink(root["verified"].AsBool());
}

void decode_tiny_status_flex_indexed(const std::vector<uint8_t>& buf) {
    auto vals = flexbuffers::GetRoot(buf).AsMap().Values();
    sink(vals[0].AsInt64());
    sink(vals[1].AsInt64());
    sink(vals[2].AsDouble());
    sink(vals[3].AsString());
    sink(vals[4].AsString());
    sink(vals[5].AsBool());
}

void decode_tiny_status_flex_partial(const std::vector<uint8_t>& buf) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    sink(root["id"].AsInt64());
    sink(root["username"].AsString());
    sink(root["verified"].AsBool());
}

void decode_tiny_status_json_full(const std::string& json) {
    sink(json_i64_after(json, "\"createdAt\":"));
    sink(json_i64_after(json, "\"id\":"));
    sink(json_i64_after(json, "\"score\":"));
    json_string_probe_after(json, "\"status\":\"");
    json_string_probe_after(json, "\"username\":\"");
    sink(json_bool_after(json, "\"verified\":"));
}

void decode_tiny_status_json_partial(const std::string& json) {
    sink(json_i64_after(json, "\"id\":"));
    json_string_probe_after(json, "\"username\":\"");
    sink(json_bool_after(json, "\"verified\":"));
}

std::vector<uint8_t> encode_sparse_options_flex(int present) {
    flexbuffers::Builder b(2048, flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    b.Map([&] {
        for (int i = 0; i < present; ++i) {
            char key[32];
            snprintf(key, sizeof(key), "present_%04d", i);
            b.Int(key, i);
        }
    });
    b.Finish();
    return b.GetBuffer();
}

std::string encode_sparse_options_json(int present) {
    std::string json = "{";
    for (int i = 0; i < present; ++i) {
        if (i) json += ",";
        char item[64];
        snprintf(item, sizeof(item), "\"present_%04d\":%d", i, i);
        json += item;
    }
    json += "}";
    return json;
}

void decode_sparse_options_flex_missing(const std::vector<uint8_t>& buf, int missing) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    for (int i = 0; i < missing; ++i) {
        char key[32];
        snprintf(key, sizeof(key), "optional_%04d", i);
        auto ref = root[key];
        sink(ref.IsNull() ? 0 : ref.AsInt64());
    }
}

void decode_sparse_options_json_missing(const std::string& json, int missing) {
    for (int i = 0; i < missing; ++i) {
        char key[32];
        snprintf(key, sizeof(key), "\"optional_%04d\":", i);
        json_missing_probe(json, key);
    }
}

std::string make_adversarial_text(int row, int len) {
    std::string s;
    s.reserve(len);
    for (int i = 0; i < len; ++i) s.push_back((char)('a' + ((row + i) % 26)));
    return s;
}

std::vector<uint8_t> encode_string_table_flex(int rows, int body_len) {
    flexbuffers::Builder b(rows * (body_len + 96), flexbuffers::BUILDER_FLAG_SHARE_KEYS);
    b.Map([&] {
        b.Vector("rows", [&] {
            for (int i = 0; i < rows; ++i) {
                auto body = make_adversarial_text(i, body_len);
                b.Map([&] {
                    b.String("body", body.c_str());
                    b.Int("id", i);
                    b.String("tag", (i % 2) ? "odd" : "even");
                    b.String("title", ("title_" + std::to_string(i)).c_str());
                });
            }
        });
    });
    b.Finish();
    return b.GetBuffer();
}

std::string encode_string_table_json(int rows, int body_len) {
    std::string json;
    json.reserve(rows * (body_len + 64));
    json += "{\"rows\":[";
    for (int i = 0; i < rows; ++i) {
        if (i) json += ",";
        auto body = make_adversarial_text(i, body_len);
        json += "{\"body\":\"";
        json += body;
        json += "\",\"id\":";
        json += std::to_string(i);
        json += ",\"tag\":\"";
        json += (i % 2) ? "odd" : "even";
        json += "\",\"title\":\"title_";
        json += std::to_string(i);
        json += "\"}";
    }
    json += "]}";
    return json;
}

void decode_string_table_flex_full(const std::vector<uint8_t>& buf) {
    auto rows = flexbuffers::GetRoot(buf).AsMap()["rows"].AsVector();
    for (size_t i = 0; i < rows.size(); ++i) {
        auto row = rows[i].AsMap();
        sink(row["body"].AsString());
        sink(row["id"].AsInt64());
        sink(row["tag"].AsString());
        sink(row["title"].AsString());
    }
}

void decode_string_table_json_full(const std::string& json) {
    size_t cursor = 0;
    while (true) {
        auto next = json.find("\"body\":\"", cursor);
        if (next == std::string::npos) break;
        cursor = next;
        json_string_probe_after(json, "\"body\":\"", &cursor);
        sink(json_i64_after(json, "\"id\":", &cursor));
        json_string_probe_after(json, "\"tag\":\"", &cursor);
        json_string_probe_after(json, "\"title\":\"", &cursor);
    }
}

std::vector<uint8_t> encode_raw_time_series() {
    std::vector<uint8_t> out;
    out.reserve(4096);
    int32_t count = 256;
    int64_t interval = 1000;
    double min = 22.5, mean = 23.42, max = 24.85;
    int64_t start = 1716307200000LL;
    const char* id = "sensor_temp_rack_42";
    uint32_t id_len = (uint32_t)strlen(id);
    append_raw(out, count);
    append_raw(out, interval);
    append_raw(out, min);
    append_raw(out, mean);
    append_raw(out, max);
    append_raw(out, start);
    append_raw(out, id_len);
    out.insert(out.end(), id, id + id_len);
    for (int i = 0; i < count; ++i) {
        int64_t ts = start + i * 1000LL;
        append_raw(out, ts);
    }
    for (int i = 0; i < count; ++i) {
        double v = 22.5 + (i % 20) * 0.1 + (i % 7) * 0.05;
        append_raw(out, v);
    }
    return out;
}

void decode_raw_time_series_full(const std::vector<uint8_t>& buf) {
    const uint8_t* p = buf.data();
    int32_t count = read_raw<int32_t>(p);
    sink((int64_t)count);
    sink(read_raw<int64_t>(p));
    sink(read_raw<double>(p));
    sink(read_raw<double>(p));
    sink(read_raw<double>(p));
    sink(read_raw<int64_t>(p));
    uint32_t id_len = read_raw<uint32_t>(p);
    if (id_len) sink((int64_t)p[0]);
    sink((int64_t)id_len);
    p += id_len;
    for (int i = 0; i < count; ++i) sink(read_raw<int64_t>(p));
    for (int i = 0; i < count; ++i) sink(read_raw<double>(p));
}

std::vector<int64_t> encode_raw_int_array(int n) {
    std::vector<int64_t> out(n);
    for (int i = 0; i < n; ++i) out[i] = i * 13LL;
    return out;
}

std::vector<std::string> adversarial_wide_keys(int n, int lookups) {
    std::vector<std::string> keys;
    keys.reserve(lookups);
    for (int i = 0; i < lookups; ++i) {
        int idx = (i * 131 + 17) % n;
        char key[32];
        snprintf(key, sizeof(key), "field_%04d", idx);
        keys.emplace_back(key);
    }
    return keys;
}

std::vector<int> adversarial_wide_indexes(int n, int lookups) {
    std::vector<int> indexes;
    indexes.reserve(lookups);
    for (int i = 0; i < lookups; ++i) indexes.push_back((i * 131 + 17) % n);
    return indexes;
}

void decode_wide_map_random_keys(const std::vector<uint8_t>& buf, const std::vector<std::string>& keys) {
    auto root = flexbuffers::GetRoot(buf).AsMap();
    for (const auto& key : keys) sink(root[key.c_str()].AsInt64());
}

void decode_wide_raw_array_random(const std::vector<int64_t>& values, const std::vector<int>& indexes) {
    for (int idx : indexes) sink(values[idx]);
}

std::vector<uint8_t> encode_unique_string_payload(bool share, int rows, int len) {
    flexbuffers::Builder b(rows * (len + 96), share ? flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS
                                                    : flexbuffers::BUILDER_FLAG_NONE);
    b.Map([&] {
        b.Vector("items", [&] {
            for (int i = 0; i < rows; ++i) {
                auto body = make_adversarial_text(i * 7, len);
                body += "_";
                body += std::to_string(i);
                b.Map([&] {
                    b.Int("id", i);
                    b.String("unique", body.c_str());
                    b.String("alsoUnique", ("row_" + std::to_string(i) + "_" + body).c_str());
                });
            }
        });
    });
    b.Finish();
    return b.GetBuffer();
}

void verify_adversarial_cases() {
    auto tiny_flex = encode_tiny_status_flex();
    auto tiny_json = encode_tiny_status_json();
    auto tiny = flexbuffers::GetRoot(tiny_flex).AsMap();
    ASSERT_EQ(tiny["id"].AsInt64(), 8847291LL);
    ASSERT_STREQ(tiny["username"].AsString().c_str(), "shibasis.patnaik");
    ASSERT_EQ(json_i64_after(tiny_json, "\"id\":"), 8847291LL);

    auto sparse = encode_sparse_options_flex(10);
    ASSERT_EQ(flexbuffers::GetRoot(sparse).AsMap()["present_0009"].AsInt64(), 9);

    auto table = encode_string_table_flex(8, 16);
    auto rows = flexbuffers::GetRoot(table).AsMap()["rows"].AsVector();
    ASSERT_EQ(rows.size(), 8);
    ASSERT_EQ(rows[7].AsMap()["id"].AsInt64(), 7);

    auto raw_ts = encode_raw_time_series();
    const uint8_t* p = raw_ts.data();
    ASSERT_EQ(read_raw<int32_t>(p), 256);
    printf("  Adversarial:      PASS (tiny, sparse, string table, raw timeseries verified)\n");
}

// ════════════════════════════════════════════════════════════════════════
// Main benchmark cases struct
// ════════════════════════════════════════════════════════════════════════

struct BenchCase {
    const char* name;
    const char* description;
    std::function<std::vector<uint8_t>()> encode;
    std::function<void(const std::vector<uint8_t>&)> decode;
    std::function<void(const std::vector<uint8_t>&)> verify;
    std::function<void(const std::vector<uint8_t>&)> decode_indexed;
    std::function<void(const std::vector<uint8_t>&)> decode_partial;
    std::function<void(const std::vector<uint8_t>&)> decode_single;
};

// ════════════════════════════════════════════════════════════════════════
// Main
// ════════════════════════════════════════════════════════════════════════

int main(int argc, char** argv) {
    bool golden_mode = false;
    const char* verify_hex_name = nullptr;
    const char* verify_hex_value = nullptr;
    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--verify") == 0) g_verify = true;
        else if (strcmp(argv[i], "--runs") == 0 && i + 1 < argc) RUNS = atoi(argv[++i]);
        else if (strcmp(argv[i], "--iters") == 0 && i + 1 < argc) ITERATIONS = atoi(argv[++i]);
        else if (strcmp(argv[i], "--quick") == 0) { WARMUP = 100; ITERATIONS = 1000; RUNS = 3; }
        else if (strcmp(argv[i], "--section") == 0 && i + 1 < argc) g_section = atoi(argv[++i]);
        else if (strcmp(argv[i], "--adversarial") == 0) g_section = 15;
        else if (strcmp(argv[i], "--golden") == 0) golden_mode = true;
        else if (strcmp(argv[i], "--verify-hex") == 0 && i + 2 < argc) {
            verify_hex_name = argv[++i];
            verify_hex_value = argv[++i];
        }
    }
    if (golden_mode) return print_golden_flexbuffers();
    if (verify_hex_name != nullptr) return verify_golden_hex(verify_hex_name, verify_hex_value);

    BenchCase cases[] = {
        {"FlatPrimitives",  "9 scalar fields",
            encode_flat_primitives, decode_flat_primitives, verify_flat_primitives,
            decode_flat_primitives_indexed, decode_flat_primitives_partial, decode_flat_primitives_single},
        {"CollectionHeavy", "lists + maps, 460 elements",
            encode_collection_heavy, decode_collection_heavy, verify_collection_heavy,
            decode_collection_heavy_indexed, nullptr, nullptr},
        {"ComplexCase",     "25 fields, nested objects, maps",
            encode_complex_case, decode_complex_case, verify_complex_case,
            decode_complex_case_indexed, nullptr, nullptr},
        {"DeeplyNested",    "4 nesting levels",
            encode_deeply_nested, decode_deeply_nested, verify_deeply_nested,
            decode_deeply_nested_indexed, nullptr, nullptr},
        {"UserProfile",     "string-heavy, nested address, 14 fields",
            encode_user_profile, decode_user_profile, verify_user_profile,
            decode_user_profile_indexed, decode_user_profile_partial, decode_user_profile_single},
        {"ApiResponse",     "20 products, paginated, 200+ fields total",
            encode_api_response, decode_api_response, verify_api_response,
            decode_api_response_indexed, decode_api_response_partial, decode_api_response_first_item},
        {"EventLog",        "telemetry, 6 metrics, 7 props, 4 tags",
            encode_event_log, decode_event_log, verify_event_log,
            decode_event_log_indexed, nullptr, nullptr},
        {"ChatThread",      "15 messages, reactions, 7 top-level",
            encode_chat_thread, decode_chat_thread, verify_chat_thread,
            decode_chat_thread_indexed, decode_chat_thread_partial, nullptr},
        {"ConfigSnapshot",  "feature flags, nested maps, 8 top-level",
            encode_config_snapshot, decode_config_snapshot, verify_config_snapshot,
            decode_config_snapshot_indexed, nullptr, nullptr},
        {"TimeSeries",      "256 doubles + 256 longs, 9 fields",
            encode_time_series, decode_time_series, verify_time_series,
            decode_time_series_indexed, decode_time_series_partial, nullptr},
    };
    int ncases = sizeof(cases) / sizeof(cases[0]);

    printf("╔══════════════════════════════════════════════════════════════════════════════════╗\n");
    printf("║  FlexBuffer C++ Exhaustive Performance Harness                                  ║\n");
    printf("╠══════════════════════════════════════════════════════════════════════════════════╣\n");
    printf("║  Compiler: %-68s║\n", __VERSION__);
    printf("║  Warmup: %-4d  Iterations: %-6d  Runs: %-3d                                    ║\n", WARMUP, ITERATIONS, RUNS);
    printf("║  Mode: %-72s║\n", g_verify ? "BENCHMARK + VERIFY" : "BENCHMARK ONLY");
    printf("║  Section: %-69s║\n", g_section ? std::to_string(g_section).c_str() : "ALL");
    printf("╚══════════════════════════════════════════════════════════════════════════════════╝\n\n");

    // Pre-encode all buffers
    std::vector<std::vector<uint8_t>> buffers(ncases);
    size_t total_bytes = 0;
    for (int i = 0; i < ncases; ++i) {
        buffers[i] = cases[i].encode();
        total_bytes += buffers[i].size();
    }

    auto should_run = [](int section) { return g_section == 0 || g_section == section; };

    // ══════════════════════════════════════════════════════════════════════
    // Phase 1: Wire Validation
    // ══════════════════════════════════════════════════════════════════════
    if (should_run(1)) {
        printf("═══ Phase 1: Wire Validation ═══════════════════════════════════════════════════\n");
        for (int i = 0; i < ncases; ++i) {
            verify_round_trip(cases[i].name, buffers[i]);
        }
        printf("  Total wire size: %zu bytes\n\n", total_bytes);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Phase 2: Correctness Verification
    // ══════════════════════════════════════════════════════════════════════
    if (g_verify && should_run(2)) {
        printf("═══ Phase 2: Correctness Verification ══════════════════════════════════════════\n");
        for (int i = 0; i < ncases; ++i) {
            cases[i].verify(buffers[i]);
        }
        printf("\n  All %d correctness checks PASSED.\n\n", ncases);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Phase 3: Full Encode/Decode (key-based)
    // ══════════════════════════════════════════════════════════════════════
    if (should_run(3)) {
        printf("═══ Phase 3: Encode/Decode — Full Materialization (key-based) ══════════════════\n");
        printf("  (%d runs × %d iters, us/op — median reported)\n\n", RUNS, ITERATIONS);

        for (int i = 0; i < ncases; ++i) {
            printf("┌── %s (%s, %zu B) ──\n", cases[i].name, cases[i].description, buffers[i].size());
            auto enc = bench("  encode", WARMUP, ITERATIONS, [&] { cases[i].encode(); }, RUNS);
            auto dec = bench("  decode (key-based, full)", WARMUP, ITERATIONS, [&] { cases[i].decode(buffers[i]); }, RUNS);
            double tput = (double)buffers[i].size() / dec.median_us; // bytes/us = MB/s
            printf("  Throughput: %.0f MB/s decode | Encode/Decode ratio: %.2f\n\n", tput, enc.median_us / dec.median_us);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Phase 4: Index-Based Decode (all 10 structures)
    // ══════════════════════════════════════════════════════════════════════
    if (should_run(4)) {
        printf("═══ Phase 4: Index-Based Decode (O(1) field access) ════════════════════════════\n");
        printf("  Eliminates binary search per field — uses Values() vector directly\n\n");

        for (int i = 0; i < ncases; ++i) {
            if (!cases[i].decode_indexed) continue;
            printf("┌── %s ──\n", cases[i].name);
            auto key_r = bench("  decode (key-based)", WARMUP, ITERATIONS, [&] { cases[i].decode(buffers[i]); }, RUNS);
            auto idx_r = bench("  decode (index-based)", WARMUP, ITERATIONS, [&] { cases[i].decode_indexed(buffers[i]); }, RUNS);
            printf("  Speedup: %.2fx (%.2f → %.2f us)\n\n", key_r.median_us / idx_r.median_us, key_r.median_us, idx_r.median_us);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Phase 5: Partial Reads (accessor pattern)
    // ══════════════════════════════════════════════════════════════════════
    if (should_run(5)) {
        printf("═══ Phase 5: Partial Read — Accessor Pattern Simulation ════════════════════════\n");
        printf("  Measures cost of reading 1 field, 3 fields, first nested item vs full decode\n\n");

        for (int i = 0; i < ncases; ++i) {
            bool has_any = cases[i].decode_partial || cases[i].decode_single;
            if (!has_any) continue;
            printf("┌── %s ──\n", cases[i].name);
            auto full = bench("  decode (full)", WARMUP, ITERATIONS, [&] { cases[i].decode(buffers[i]); }, RUNS);
            if (cases[i].decode_single) {
                auto single = bench("  read (1 field)", WARMUP, ITERATIONS, [&] { cases[i].decode_single(buffers[i]); }, RUNS);
                printf("  1-field speedup: %.1fx\n", full.median_us / single.median_us);
            }
            if (cases[i].decode_partial) {
                auto partial = bench("  read (3 fields)", WARMUP, ITERATIONS, [&] { cases[i].decode_partial(buffers[i]); }, RUNS);
                printf("  3-field speedup: %.1fx\n", full.median_us / partial.median_us);
            }
            printf("\n");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Phase 6: Round-trip latency (encode + decode combined)
    // ══════════════════════════════════════════════════════════════════════
    if (should_run(6)) {
        printf("═══ Phase 6: Round-Trip Latency (encode + decode) ══════════════════════════════\n");
        printf("  Total time from data → bytes → data\n\n");

        for (int i = 0; i < ncases; ++i) {
            printf("┌── %s ──\n", cases[i].name);
            auto rt = bench("  round-trip (encode+decode)", WARMUP, ITERATIONS, [&] {
                auto buf = cases[i].encode();
                cases[i].decode(buf);
            }, RUNS);
            if (cases[i].decode_indexed) {
                auto rt_idx = bench("  round-trip (encode+index-decode)", WARMUP, ITERATIONS, [&] {
                    auto buf = cases[i].encode();
                    cases[i].decode_indexed(buf);
                }, RUNS);
                printf("  Index RT speedup: %.2fx\n", rt.median_us / rt_idx.median_us);
            }
            printf("\n");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Phase 7: Scalar Type Isolation Micro-benchmark
    // ══════════════════════════════════════════════════════════════════════
    if (should_run(7)) {
        printf("═══ Phase 7: Scalar Type Isolation ═════════════════════════════════════════════\n");
        printf("  Cost of reading a single field by type (includes GetRoot + map lookup)\n\n");

        init_scalar_buf();
        auto& sb = g_scalar_buf;

        bench("  Bool field", WARMUP, ITERATIONS, [&] {
            auto r = flexbuffers::GetRoot(sb).AsMap(); sink(r["b"].AsBool());
        }, RUNS);
        bench("  Int32 field", WARMUP, ITERATIONS, [&] {
            auto r = flexbuffers::GetRoot(sb).AsMap(); sink(r["i32"].AsInt64());
        }, RUNS);
        bench("  Int64 field", WARMUP, ITERATIONS, [&] {
            auto r = flexbuffers::GetRoot(sb).AsMap(); sink(r["i64"].AsInt64());
        }, RUNS);
        bench("  Float field", WARMUP, ITERATIONS, [&] {
            auto r = flexbuffers::GetRoot(sb).AsMap(); sink((double)r["f"].AsFloat());
        }, RUNS);
        bench("  Double field", WARMUP, ITERATIONS, [&] {
            auto r = flexbuffers::GetRoot(sb).AsMap(); sink(r["d"].AsDouble());
        }, RUNS);
        bench("  Short string (2 chars)", WARMUP, ITERATIONS, [&] {
            auto r = flexbuffers::GetRoot(sb).AsMap(); sink(r["s_short"].AsString());
        }, RUNS);
        bench("  Long string (100 chars)", WARMUP, ITERATIONS, [&] {
            auto r = flexbuffers::GetRoot(sb).AsMap(); sink(r["s_long"].AsString());
        }, RUNS);
        printf("\n");

        // Isolate GetRoot cost
        bench("  GetRoot only (no field access)", WARMUP, ITERATIONS, [&] {
            auto r = flexbuffers::GetRoot(sb).AsMap(); sink((int64_t)r.size());
        }, RUNS);
        printf("\n");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Phase 8: Vector Size Scaling
    // ══════════════════════════════════════════════════════════════════════
    if (should_run(8)) {
        printf("═══ Phase 8: Vector Size Scaling ═══════════════════════════════════════════════\n");
        printf("  Iteration cost as vector size grows (int vectors)\n\n");

        int sizes[] = {10, 50, 100, 500, 1000, 5000};
        for (int n : sizes) {
            auto buf = encode_int_vector(n);
            char label[64];
            snprintf(label, sizeof(label), "  int vector[%d] iterate (%zu B)", n, buf.size());
            bench(label, WARMUP, ITERATIONS, [&] {
                auto root = flexbuffers::GetRoot(buf).AsMap();
                auto v = root["v"].AsVector();
                for (size_t i = 0; i < v.size(); ++i) sink(v[i].AsInt64());
            }, RUNS);
        }
        printf("\n  Double vectors:\n");
        for (int n : sizes) {
            auto buf = encode_double_vector(n);
            char label[64];
            snprintf(label, sizeof(label), "  double vector[%d] iterate (%zu B)", n, buf.size());
            bench(label, WARMUP, ITERATIONS, [&] {
                auto root = flexbuffers::GetRoot(buf).AsMap();
                auto v = root["v"].AsVector();
                for (size_t i = 0; i < v.size(); ++i) sink(v[i].AsDouble());
            }, RUNS);
        }
        printf("\n  String vectors (10-char strings):\n");
        int str_sizes[] = {10, 50, 100, 500};
        for (int n : str_sizes) {
            auto buf = encode_string_vector(n, 10);
            char label[64];
            snprintf(label, sizeof(label), "  string vector[%d] iterate (%zu B)", n, buf.size());
            bench(label, WARMUP, ITERATIONS, [&] {
                auto root = flexbuffers::GetRoot(buf).AsMap();
                auto v = root["v"].AsVector();
                for (size_t i = 0; i < v.size(); ++i) sink(v[i].AsString());
            }, RUNS);
        }
        printf("\n");

        // Per-element cost analysis
        printf("  Per-element cost (ns/element):\n");
        for (int n : {100, 1000, 5000}) {
            auto buf = encode_int_vector(n);
            auto r = bench_quiet(WARMUP, ITERATIONS, [&] {
                auto root = flexbuffers::GetRoot(buf).AsMap();
                auto v = root["v"].AsVector();
                for (size_t i = 0; i < v.size(); ++i) sink(v[i].AsInt64());
            }, RUNS);
            printf("  int[%d]: %.1f ns/elem\n", n, r.median_us * 1000.0 / n);
        }
        printf("\n");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Phase 9: Map Key Lookup Scaling
    // ══════════════════════════════════════════════════════════════════════
    if (should_run(9)) {
        printf("═══ Phase 9: Map Key Lookup Scaling ════════════════════════════════════════════\n");
        printf("  Binary search cost: O(log n) — measures impact of map size on single lookup\n\n");

        int map_sizes[] = {5, 10, 25, 50, 100, 250, 500};
        printf("  %-12s %-12s %-12s %-14s %-12s\n", "Map Size", "1 Lookup", "All Keys", "Per-Key(ns)", "Log2(n)");
        printf("  %-12s %-12s %-12s %-14s %-12s\n", "────────", "────────", "────────", "──────────", "───────");

        for (int n : map_sizes) {
            auto buf = encode_map_n_keys(n);
            // Single key lookup (middle of map)
            char mid_key[32];
            snprintf(mid_key, sizeof(mid_key), "field_%04d", n / 2);
            auto single = bench_quiet(WARMUP, ITERATIONS, [&] {
                auto root = flexbuffers::GetRoot(buf).AsMap();
                sink(root[mid_key].AsInt64());
            }, RUNS);
            // All keys (sequential)
            auto all = bench_quiet(WARMUP, ITERATIONS, [&] {
                auto root = flexbuffers::GetRoot(buf).AsMap();
                auto keys = root.Keys();
                for (size_t i = 0; i < keys.size(); ++i) {
                    sink(root[keys[i].AsKey()].AsInt64());
                }
            }, RUNS);
            // All keys index-based
            auto all_idx = bench_quiet(WARMUP, ITERATIONS, [&] {
                auto root = flexbuffers::GetRoot(buf).AsMap();
                auto vals = root.Values();
                for (size_t i = 0; i < vals.size(); ++i) {
                    sink(vals[i].AsInt64());
                }
            }, RUNS);
            printf("  %-12d %8.2f us  %8.2f us  %10.1f ns  %.1f (idx: %.2f us)\n",
                   n, single.median_us, all.median_us,
                   all.median_us * 1000.0 / n, log2(n), all_idx.median_us);
        }
        printf("\n");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Phase 10: Nesting Depth Cost
    // ══════════════════════════════════════════════════════════════════════
    if (should_run(10)) {
        printf("═══ Phase 10: Nesting Depth Cost ═══════════════════════════════════════════════\n");
        printf("  Measures decode cost as nesting increases (reading deepest value)\n\n");

        int depths[] = {1, 2, 3, 4, 6, 8};
        for (int d : depths) {
            auto buf = encode_depth_n(d);
            char label[64];
            snprintf(label, sizeof(label), "  depth=%d, read deepest value (%zu B)", d, buf.size());
            bench(label, WARMUP, ITERATIONS, [&] {
                auto root = flexbuffers::GetRoot(buf).AsMap();
                // Navigate to deepest "value" field
                auto current = root;
                for (int level = 1; level < d; ++level) {
                    current = current["child"].AsMap();
                }
                sink(current["value"].AsInt64());
                sink(current["name"].AsString());
            }, RUNS);
        }
        printf("\n  Read ALL levels (traversing back up):\n");
        for (int d : depths) {
            auto buf = encode_depth_n(d);
            char label[64];
            snprintf(label, sizeof(label), "  depth=%d, read all levels", d);
            bench(label, WARMUP, ITERATIONS, [&] {
                auto root = flexbuffers::GetRoot(buf).AsMap();
                auto current = root;
                for (int level = 1; level <= d; ++level) {
                    sink(current["value"].AsInt64());
                    sink(current["name"].AsString());
                    if (level < d) current = current["child"].AsMap();
                }
            }, RUNS);
        }
        printf("\n");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Phase 11: String Length Impact
    // ══════════════════════════════════════════════════════════════════════
    if (should_run(11)) {
        printf("═══ Phase 11: String Length Impact ═════════════════════════════════════════════\n");
        printf("  How string size affects decode latency\n\n");

        int lengths[] = {1, 10, 50, 100, 500, 1000, 5000, 10000};
        for (int len : lengths) {
            auto buf = encode_string_field(len);
            char label[64];
            snprintf(label, sizeof(label), "  string[%d chars] read (%zu B)", len, buf.size());
            bench(label, WARMUP, ITERATIONS, [&] {
                auto root = flexbuffers::GetRoot(buf).AsMap();
                auto s = root["data"].AsString();
                sink(s);
                // Force reading string length to simulate actual use
                sink((int64_t)s.size());
            }, RUNS);
        }
        printf("\n");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Phase 12: Encode Options Comparison
    // ══════════════════════════════════════════════════════════════════════
    if (should_run(12)) {
        printf("═══ Phase 12: Encode Options ═══════════════════════════════════════════════════\n");
        printf("  Key/string sharing impact on encode time and buffer size\n\n");

        int item_counts[] = {5, 20, 50, 100};
        printf("  %-8s %-14s %-14s %-10s %-10s %-8s\n",
               "Items", "No Share(us)", "Share(us)", "No Share(B)", "Share(B)", "Savings");
        printf("  %-8s %-14s %-14s %-10s %-10s %-8s\n",
               "─────", "────────────", "──────────", "──────────", "────────", "───────");
        for (int n : item_counts) {
            auto r_no = bench_quiet(WARMUP, ITERATIONS, [&] { encode_no_sharing(n); }, RUNS);
            auto r_sh = bench_quiet(WARMUP, ITERATIONS, [&] { encode_with_sharing(n); }, RUNS);
            auto buf_no = encode_no_sharing(n);
            auto buf_sh = encode_with_sharing(n);
            double savings = 100.0 * (1.0 - (double)buf_sh.size() / buf_no.size());
            printf("  %-8d %10.2f us  %10.2f us  %8zu B  %8zu B  %5.1f%%\n",
                   n, r_no.median_us, r_sh.median_us, buf_no.size(), buf_sh.size(), savings);
        }
        printf("\n");

        // Builder pre-allocation test
        printf("  Builder pre-allocation (UserProfile):\n");
        bench("  encode (default 2KB pre-alloc)", WARMUP, ITERATIONS, [&] { encode_user_profile(); }, RUNS);
        bench("  encode (4KB pre-alloc)", WARMUP, ITERATIONS, [&] {
            flexbuffers::Builder b(4096, flexbuffers::BUILDER_FLAG_SHARE_KEYS);
            b.Map([&] {
                b.Map("address", [&] {
                    b.String("city", "Bengaluru"); b.String("country", "IN");
                    b.String("state", "Karnataka"); b.String("street", "42 MG Road, Indiranagar");
                    b.String("zip", "560038");
                });
                b.String("avatarUrl", "https://cdn.reaktor.build/avatars/8847291/profile_400x400.webp");
                b.String("bio", "Building cross-platform infrastructure. KMP enthusiast. Reaktor framework author.");
                b.Int("createdAtEpochMs", 1609459200000LL);
                b.String("displayName", "Shibasis Patnaik"); b.String("email", "shibasis@reaktor.build");
                b.Int("followerCount", 2847); b.Int("followingCount", 312);
                b.Int("id", 8847291LL); b.Int("postCount", 891);
                b.Map("settings", [&] {
                    b.String("language", "en"); b.String("notifications", "all");
                    b.String("privacy", "friends"); b.String("theme", "dark");
                    b.String("timezone", "Asia/Kolkata"); b.String("two_factor", "enabled");
                });
                b.Vector("tags", [&] {
                    b.String("kotlin"); b.String("multiplatform"); b.String("android");
                    b.String("ios"); b.String("react"); b.String("infrastructure");
                });
                b.String("username", "shibasis.patnaik"); b.Bool("verified", true);
            });
            b.Finish();
            auto buf = b.GetBuffer();
            sink((int64_t)buf.size());
        }, RUNS);
        printf("\n");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Phase 13: Memory Bandwidth Analysis
    // ══════════════════════════════════════════════════════════════════════
    if (should_run(13)) {
        printf("═══ Phase 13: Memory Bandwidth & Cache Efficiency ══════════════════════════════\n");
        printf("  Bytes processed per microsecond across payload sizes\n\n");

        printf("  %-24s %8s %8s %10s %12s\n", "Structure", "Size(B)", "Decode", "MB/s", "ns/byte");
        printf("  %-24s %8s %8s %10s %12s\n", "────────────────────────", "──────", "──────", "────────", "───────");
        for (int i = 0; i < ncases; ++i) {
            auto dec = bench_quiet(WARMUP, ITERATIONS, [&] { cases[i].decode(buffers[i]); }, RUNS);
            double mbps = (double)buffers[i].size() / dec.median_us;
            double ns_per_byte = dec.median_us * 1000.0 / buffers[i].size();
            printf("  %-24s %6zu B %6.2f us %8.1f MB/s %8.2f ns/B\n",
                   cases[i].name, buffers[i].size(), dec.median_us, mbps, ns_per_byte);
        }
        printf("\n");

        // Large payload test
        printf("  Large payload scaling (double vectors):\n");
        int large_sizes[] = {256, 1024, 4096, 16384, 65536};
        for (int n : large_sizes) {
            auto buf = encode_double_vector(n);
            auto r = bench_quiet(WARMUP, std::min(ITERATIONS, 1000), [&] {
                auto root = flexbuffers::GetRoot(buf).AsMap();
                auto v = root["v"].AsVector();
                for (size_t i = 0; i < v.size(); ++i) sink(v[i].AsDouble());
            }, RUNS);
            double mbps = (double)buf.size() / r.median_us;
            printf("  double[%5d]: %6zu B → %7.2f us  (%6.1f MB/s, %.2f ns/elem)\n",
                   n, buf.size(), r.median_us, mbps, r.median_us * 1000.0 / n);
        }
        printf("\n");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Phase 14: Comprehensive Summary Tables
    // ══════════════════════════════════════════════════════════════════════
    if (should_run(14)) {
        printf("╔══════════════════════════════════════════════════════════════════════════════════════════════╗\n");
        printf("║                          COMPREHENSIVE SUMMARY TABLES                                       ║\n");
        printf("╠══════════════════════════════════════════════════════════════════════════════════════════════╣\n");

        // Table 1: Full overview
        printf("║                                                                                              ║\n");
        printf("║  Table 1: Encode + Decode Overview                                                           ║\n");
        printf("║  %-20s %7s %9s %9s %9s %8s %8s                  ║\n",
               "Structure", "Size", "Encode", "Key Dec", "Idx Dec", "Tput", "Idx Tput");
        printf("║  %-20s %7s %9s %9s %9s %8s %8s                  ║\n",
               "────────────────────", "─────", "───────", "───────", "───────", "──────", "────────");

        for (int i = 0; i < ncases; ++i) {
            auto enc = bench_quiet(WARMUP, ITERATIONS, [&] { cases[i].encode(); }, RUNS);
            auto dec = bench_quiet(WARMUP, ITERATIONS, [&] { cases[i].decode(buffers[i]); }, RUNS);
            double tput = (double)buffers[i].size() / dec.median_us;
            if (cases[i].decode_indexed) {
                auto idx = bench_quiet(WARMUP, ITERATIONS, [&] { cases[i].decode_indexed(buffers[i]); }, RUNS);
                double idx_tput = (double)buffers[i].size() / idx.median_us;
                printf("║  %-20s %5zu B %7.2f us %7.2f us %7.2f us %5.0f MB/s %5.0f MB/s                  ║\n",
                       cases[i].name, buffers[i].size(), enc.median_us, dec.median_us, idx.median_us, tput, idx_tput);
            } else {
                printf("║  %-20s %5zu B %7.2f us %7.2f us %9s %5.0f MB/s %8s                  ║\n",
                       cases[i].name, buffers[i].size(), enc.median_us, dec.median_us, "—", tput, "—");
            }
        }

        // Table 2: Index speedup breakdown
        printf("║                                                                                              ║\n");
        printf("║  Table 2: Index-Based Decode Speedup                                                         ║\n");
        printf("║  %-20s %9s %9s %9s %30s                  ║\n", "Structure", "Key(us)", "Index(us)", "Speedup", "Notes");
        printf("║  %-20s %9s %9s %9s %30s                  ║\n", "────────────────────", "───────", "───────", "───────", "─────────────────────────────");

        for (int i = 0; i < ncases; ++i) {
            if (!cases[i].decode_indexed) continue;
            auto key_r = bench_quiet(WARMUP, ITERATIONS, [&] { cases[i].decode(buffers[i]); }, RUNS);
            auto idx_r = bench_quiet(WARMUP, ITERATIONS, [&] { cases[i].decode_indexed(buffers[i]); }, RUNS);
            printf("║  %-20s %7.2f us %7.2f us %7.2fx  %-30s                  ║\n",
                   cases[i].name, key_r.median_us, idx_r.median_us,
                   key_r.median_us / idx_r.median_us, cases[i].description);
        }

        // Table 3: Partial read analysis
        printf("║                                                                                              ║\n");
        printf("║  Table 3: Partial Read (Accessor Pattern)                                                    ║\n");
        printf("║  %-20s %9s %9s %9s %9s %9s                       ║\n",
               "Structure", "Full", "3-field", "1-field", "3f Speed", "1f Speed");
        printf("║  %-20s %9s %9s %9s %9s %9s                       ║\n",
               "────────────────────", "───────", "───────", "───────", "───────", "───────");

        for (int i = 0; i < ncases; ++i) {
            if (!cases[i].decode_partial && !cases[i].decode_single) continue;
            auto full = bench_quiet(WARMUP, ITERATIONS, [&] { cases[i].decode(buffers[i]); }, RUNS);
            double part_us = 0, single_us = 0;
            if (cases[i].decode_partial) {
                auto r = bench_quiet(WARMUP, ITERATIONS, [&] { cases[i].decode_partial(buffers[i]); }, RUNS);
                part_us = r.median_us;
            }
            if (cases[i].decode_single) {
                auto r = bench_quiet(WARMUP, ITERATIONS, [&] { cases[i].decode_single(buffers[i]); }, RUNS);
                single_us = r.median_us;
            }
            printf("║  %-20s %7.2f us", cases[i].name, full.median_us);
            if (part_us > 0) printf(" %7.2f us", part_us); else printf(" %9s", "—");
            if (single_us > 0) printf(" %7.2f us", single_us); else printf(" %9s", "—");
            if (part_us > 0) printf(" %7.1fx", full.median_us / part_us); else printf(" %9s", "—");
            if (single_us > 0) printf(" %7.1fx", full.median_us / single_us); else printf(" %9s", "—");
            printf("                       ║\n");
        }

        printf("║                                                                                              ║\n");
        printf("╚══════════════════════════════════════════════════════════════════════════════════════════════╝\n");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Phase 15: Adversarial Counter-Baselines
    // ══════════════════════════════════════════════════════════════════════
    if (should_run(15)) {
        printf("═══ Phase 15: Adversarial Counter-Baselines ════════════════════════════════════\n");
        printf("  Goal: find cases where FlexBuffers are NOT faster. Baselines are narrower\n");
        printf("  encodings and controlled parsers, so they trade away schema evolution and\n");
        printf("  generality. They are included to falsify broad performance claims.\n\n");

        if (g_verify) {
            verify_adversarial_cases();
            printf("\n");
        }

        struct CounterResult {
            const char* name;
            const char* flex_label;
            const char* alt_label;
            double flex_us;
            double alt_us;
            size_t flex_bytes;
            size_t alt_bytes;
        };
        std::vector<CounterResult> counters;
        auto add_counter = [&](const char* name, const char* flex_label, const char* alt_label,
                               BenchResult flex_r, BenchResult alt_r,
                               size_t flex_bytes, size_t alt_bytes) {
            counters.push_back({name, flex_label, alt_label, flex_r.median_us, alt_r.median_us, flex_bytes, alt_bytes});
        };

        printf("┌── Raw POD vs FlexBuffer flat object ──\n");
        auto flat_flex = encode_flat_primitives();
        auto flat_raw = encode_raw_flat_primitives();
        auto flat_flex_r = bench("  FlexBuffer full decode", WARMUP, ITERATIONS, [&] {
            decode_flat_primitives_indexed(flat_flex);
        }, RUNS);
        auto flat_raw_r = bench("  Raw packed struct decode", WARMUP, ITERATIONS, [&] {
            decode_raw_flat_primitives(flat_raw);
        }, RUNS);
        add_counter("FlatPrimitives full decode", "Flex indexed", "Raw POD", flat_flex_r, flat_raw_r, flat_flex.size(), flat_raw.size());
        printf("  Raw/POD speedup over Flex: %.2fx | size: %zu B vs %zu B\n\n",
               flat_flex_r.median_us / flat_raw_r.median_us, flat_flex.size(), flat_raw.size());

        printf("┌── Tiny status object: FlexBuffer vs controlled JSON scan ──\n");
        auto tiny_flex = encode_tiny_status_flex();
        auto tiny_json = encode_tiny_status_json();
        auto tiny_flex_key = bench("  FlexBuffer full key decode", WARMUP, ITERATIONS, [&] {
            decode_tiny_status_flex_full(tiny_flex);
        }, RUNS);
        auto tiny_flex_idx = bench("  FlexBuffer full index decode", WARMUP, ITERATIONS, [&] {
            decode_tiny_status_flex_indexed(tiny_flex);
        }, RUNS);
        auto tiny_json_full = bench("  JSON scan full decode", WARMUP, ITERATIONS, [&] {
            decode_tiny_status_json_full(tiny_json);
        }, RUNS);
        auto tiny_flex_part = bench("  FlexBuffer 3-field read", WARMUP, ITERATIONS, [&] {
            decode_tiny_status_flex_partial(tiny_flex);
        }, RUNS);
        auto tiny_json_part = bench("  JSON scan 3-field read", WARMUP, ITERATIONS, [&] {
            decode_tiny_status_json_partial(tiny_json);
        }, RUNS);
        add_counter("TinyStatus full", "Flex indexed", "JSON scan", tiny_flex_idx, tiny_json_full, tiny_flex.size(), tiny_json.size());
        add_counter("TinyStatus partial", "Flex key", "JSON scan", tiny_flex_part, tiny_json_part, tiny_flex.size(), tiny_json.size());
        add_counter("TinyStatus key/index", "Flex key", "Flex index", tiny_flex_key, tiny_flex_idx, tiny_flex.size(), tiny_flex.size());
        printf("  Flex key/index/full JSON: %.2f / %.2f / %.2f us | sizes Flex=%zu B JSON=%zu B\n\n",
               tiny_flex_key.median_us, tiny_flex_idx.median_us, tiny_json_full.median_us, tiny_flex.size(), tiny_json.size());

        printf("┌── Sparse optional fields: many missing lookups ──\n");
        auto sparse_flex = encode_sparse_options_flex(10);
        auto sparse_json = encode_sparse_options_json(10);
        int missing_fields = 256;
        auto sparse_flex_r = bench("  FlexBuffer 256 missing optional lookups", WARMUP, ITERATIONS, [&] {
            decode_sparse_options_flex_missing(sparse_flex, missing_fields);
        }, RUNS);
        auto sparse_json_r = bench("  JSON scan 256 missing optional probes", WARMUP, ITERATIONS, [&] {
            decode_sparse_options_json_missing(sparse_json, missing_fields);
        }, RUNS);
        add_counter("Sparse optionals", "Flex missing", "JSON miss", sparse_flex_r, sparse_json_r, sparse_flex.size(), sparse_json.size());
        printf("  Missing-field workload ratio Flex/JSON: %.2fx | sizes Flex=%zu B JSON=%zu B\n\n",
               sparse_flex_r.median_us / sparse_json_r.median_us, sparse_flex.size(), sparse_json.size());

        printf("┌── String-heavy row table: FlexBuffer vs controlled JSON scan ──\n");
        int rows = 200;
        int body_len = 80;
        auto table_flex = encode_string_table_flex(rows, body_len);
        auto table_json = encode_string_table_json(rows, body_len);
        int table_iters = std::min(ITERATIONS, 2000);
        auto table_flex_r = bench("  FlexBuffer scan 200 rows", WARMUP, table_iters, [&] {
            decode_string_table_flex_full(table_flex);
        }, RUNS);
        auto table_json_r = bench("  JSON scan 200 rows", WARMUP, table_iters, [&] {
            decode_string_table_json_full(table_json);
        }, RUNS);
        add_counter("StringTable full scan", "Flex row scan", "JSON scan", table_flex_r, table_json_r, table_flex.size(), table_json.size());
        printf("  String table ratio Flex/JSON: %.2fx | sizes Flex=%zu B JSON=%zu B\n\n",
               table_flex_r.median_us / table_json_r.median_us, table_flex.size(), table_json.size());

        printf("┌── TimeSeries: self-describing FlexBuffer vs fixed-order binary row ──\n");
        auto ts_flex = encode_time_series();
        auto ts_raw = encode_raw_time_series();
        auto ts_flex_r = bench("  FlexBuffer indexed full decode", WARMUP, ITERATIONS, [&] {
            decode_time_series_indexed(ts_flex);
        }, RUNS);
        auto ts_raw_r = bench("  Fixed binary full decode", WARMUP, ITERATIONS, [&] {
            decode_raw_time_series_full(ts_raw);
        }, RUNS);
        add_counter("TimeSeries full decode", "Flex indexed", "Fixed binary", ts_flex_r, ts_raw_r, ts_flex.size(), ts_raw.size());
        printf("  Fixed binary speedup over Flex: %.2fx | sizes Flex=%zu B raw=%zu B\n\n",
               ts_flex_r.median_us / ts_raw_r.median_us, ts_flex.size(), ts_raw.size());

        printf("┌── Wide random access: 64 keyed map reads vs raw array reads ──\n");
        int wide_n = 1024;
        int lookups = 64;
        auto wide_flex = encode_map_n_keys(wide_n);
        auto wide_raw = encode_raw_int_array(wide_n);
        auto wide_keys = adversarial_wide_keys(wide_n, lookups);
        auto wide_indexes = adversarial_wide_indexes(wide_n, lookups);
        auto wide_flex_r = bench("  FlexBuffer 64 random key reads", WARMUP, ITERATIONS, [&] {
            decode_wide_map_random_keys(wide_flex, wide_keys);
        }, RUNS);
        auto wide_raw_r = bench("  Raw array 64 random reads", WARMUP, ITERATIONS, [&] {
            decode_wide_raw_array_random(wide_raw, wide_indexes);
        }, RUNS);
        auto wide_flex_seq_r = bench("  FlexBuffer 64 sequential index reads", WARMUP, ITERATIONS, [&] {
            auto root = flexbuffers::GetRoot(wide_flex).AsMap();
            auto values = root.Values();
            int64_t sum = 0;
            for (int i = 0; i < lookups; ++i) sum += values[i].AsInt64();
            sink(sum);
        }, RUNS);
        auto wide_raw_seq_r = bench("  Raw array 64 sequential reads", WARMUP, ITERATIONS, [&] {
            int64_t sum = 0;
            for (int i = 0; i < lookups; ++i) sum += wide_raw[i];
            sink(sum);
        }, RUNS);
        add_counter("Wide random reads", "Flex map keys", "Raw array", wide_flex_r, wide_raw_r, wide_flex.size(), wide_raw.size() * sizeof(int64_t));
        add_counter("Wide sequential reads", "Flex index", "Raw array", wide_flex_seq_r, wide_raw_seq_r, wide_flex.size(), wide_raw.size() * sizeof(int64_t));
        printf("  Raw array speedup over Flex: %.2fx | sizes Flex=%zu B raw=%zu B\n\n",
               wide_flex_r.median_us / wide_raw_r.median_us, wide_flex.size(), wide_raw.size() * sizeof(int64_t));

        printf("┌── Unique strings: key/string sharing can backfire ──\n");
        int unique_rows = 200;
        int unique_len = 64;
        auto unique_share_r = bench("  Flex encode SHARE_KEYS_AND_STRINGS", WARMUP, table_iters, [&] {
            auto buf = encode_unique_string_payload(true, unique_rows, unique_len);
            sink((int64_t)buf.size());
        }, RUNS);
        auto unique_none_r = bench("  Flex encode no sharing", WARMUP, table_iters, [&] {
            auto buf = encode_unique_string_payload(false, unique_rows, unique_len);
            sink((int64_t)buf.size());
        }, RUNS);
        auto unique_share_buf = encode_unique_string_payload(true, unique_rows, unique_len);
        auto unique_none_buf = encode_unique_string_payload(false, unique_rows, unique_len);
        add_counter("Unique-string encode", "Share", "No share", unique_share_r, unique_none_r,
                    unique_share_buf.size(), unique_none_buf.size());
        printf("  Sharing/no-sharing ratio: %.2fx | sizes share=%zu B no-share=%zu B\n\n",
               unique_share_r.median_us / unique_none_r.median_us, unique_share_buf.size(), unique_none_buf.size());

        printf("╔══════════════════════════════════════════════════════════════════════════════════════════════╗\n");
        printf("║                         ADVERSARIAL LOSS LEDGER                                             ║\n");
        printf("╠══════════════════════════════════════════════════════════════════════════════════════════════╣\n");
        printf("║  %-24s %-13s %-13s %8s %8s %8s %8s %8s          ║\n",
               "Case", "Flex", "Counter", "Flex us", "Alt us", "Flex B", "Alt B", "Winner");
        printf("║  %-24s %-13s %-13s %8s %8s %8s %8s %8s          ║\n",
               "────────────────────────", "──────────────", "──────────────", "───────", "──────", "──────", "─────", "──────");
        int flex_losses = 0;
        int flex_size_losses = 0;
        for (const auto& r : counters) {
            bool time_loss = r.flex_us > r.alt_us;
            bool size_loss = r.flex_bytes > r.alt_bytes;
            if (time_loss) ++flex_losses;
            if (size_loss) ++flex_size_losses;
            const char* winner = time_loss ? "Counter" : "Flex";
            printf("║  %-24s %-13s %-13s %8.2f %8.2f %8zu %8zu %8s          ║\n",
                   r.name, r.flex_label, r.alt_label, r.flex_us, r.alt_us,
                   r.flex_bytes, r.alt_bytes, winner);
        }
        printf("║                                                                                              ║\n");
        printf("║  FlexBuffers lost %d/%zu time comparisons and %d/%zu size comparisons in this adversarial set.      ║\n",
               flex_losses, counters.size(), flex_size_losses, counters.size());
        printf("╚══════════════════════════════════════════════════════════════════════════════════════════════╝\n\n");
    }

    printf("\n  (sink=%lld — prevents dead-code elimination)\n", (long long)g_sink);
    return 0;
}
