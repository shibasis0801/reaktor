/*
 * Reaktor FlatBuffers vs FlexBuffers C++ benchmark.
 *
 * This benchmark intentionally does not use generated FlatBuffers headers.
 * It uses the same low-level table/vtable/vector primitives generated code
 * uses, which keeps the file self-contained while still measuring the binary
 * layout difference:
 *
 *   FlexBuffers: self-describing map/vector document with keys and type tags.
 *   FlatBuffers: fixed schema table/vector document with vtable field slots.
 *
 * Build:
 *   clang++ -O3 -DNDEBUG -mcpu=native -std=c++17 \
 *     -I ../../../.github_modules/flatbuffers/include \
 *     flatbuffers_vs_flexbuffers.cpp -o flatbuffers_vs_flexbuffers
 *
 * Run:
 *   ./flatbuffers_vs_flexbuffers
 *   ./flatbuffers_vs_flexbuffers --quick
 *   ./flatbuffers_vs_flexbuffers --verify-only
 *   ./flatbuffers_vs_flexbuffers --iters 50000 --runs 9
 */

#include <flatbuffers/flatbuffers.h>
#include <flatbuffers/flexbuffers.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <numeric>
#include <string>
#include <type_traits>
#include <utility>
#include <vector>

namespace {

using Bytes = std::vector<uint8_t>;
using FbTable = flatbuffers::Table;
using FbString = flatbuffers::String;
using FbTableOffset = flatbuffers::Offset<FbTable>;
using FbStringOffset = flatbuffers::Offset<FbString>;
using FbTableVec = flatbuffers::Vector<flatbuffers::Offset<FbTable>>;
using FbStringVec = flatbuffers::Vector<flatbuffers::Offset<FbString>>;

int warmup = 1000;
int iterations = 10000;
int runs = 7;
bool verify_only = false;
volatile uint64_t sink_value = 0;

constexpr flatbuffers::voffset_t fslot(int index) {
    return static_cast<flatbuffers::voffset_t>(4 + index * 2);
}

// The timed readers build one local checksum. Its destructor performs exactly
// one volatile store per operation. This prevents the
// anti-optimization guard itself from dominating field access, while the type
// tags and complete string contents make cross-format verification meaningful.
class Checksum {
public:
    ~Checksum() { sink_value = state_; }

    template <typename T, typename std::enable_if<
        std::is_integral<T>::value && !std::is_same<T, bool>::value,
        int>::type = 0>
    void add(T value) {
        mixByte(1);
        mixWord(static_cast<uint64_t>(static_cast<int64_t>(value)));
    }

    void add(bool value) {
        mixByte(2);
        mixByte(value ? 1 : 0);
    }

    void add(double value) {
        uint64_t bits = 0;
        static_assert(sizeof(bits) == sizeof(value), "unexpected double width");
        std::memcpy(&bits, &value, sizeof(bits));
        mixByte(3);
        mixWord(bits);
    }

    void addFlexString(const flexbuffers::String& value) {
        addString(value.c_str(), value.size());
    }

    void addFbString(const FbString* value) {
        if (!value) {
            mixByte(4);
            mixWord(UINT64_MAX);
            return;
        }
        addString(value->c_str(), value->size());
    }

    uint64_t value() const { return state_; }

private:
    void addString(const char* data, size_t size) {
        mixByte(4);
        mixWord(static_cast<uint64_t>(size));
        for (size_t i = 0; i < size; ++i) {
            mixByte(static_cast<uint8_t>(data[i]));
        }
    }

    void mixWord(uint64_t word) {
        for (int shift = 0; shift < 64; shift += 8) {
            mixByte(static_cast<uint8_t>(word >> shift));
        }
    }

    void mixByte(uint8_t byte) {
        state_ ^= byte;
        state_ *= 1099511628211ULL;
    }

    uint64_t state_ = 1469598103934665603ULL;
};

#define sink(value) checksum.add(value)
#define sinkFlexString(value) checksum.addFlexString(value)
#define sinkFbString(value) checksum.addFbString(value)

const FbTable* rootTable(const Bytes& bytes) {
    return flatbuffers::GetRoot<FbTable>(bytes.data());
}

Bytes finishFlat(flatbuffers::FlatBufferBuilder& builder, FbTableOffset root) {
    builder.Finish(root);
    const uint8_t* begin = builder.GetBufferPointer();
    return Bytes(begin, begin + builder.GetSize());
}

FbTableOffset endTable(flatbuffers::FlatBufferBuilder& builder, flatbuffers::uoffset_t start) {
    return FbTableOffset(builder.EndTable(start));
}

std::string token(const char* prefix, int i) {
    return std::string(prefix) + "_" + std::to_string(i);
}

struct BenchStats {
    double minimum;
    double median;
    double maximum;
    double mean;
    double standard_deviation;
};

template <typename Fn>
BenchStats bench(Fn&& fn) {
    for (int i = 0; i < warmup; ++i) fn();

    std::vector<double> samples;
    samples.reserve(static_cast<size_t>(runs));
    for (int r = 0; r < runs; ++r) {
        auto start = std::chrono::high_resolution_clock::now();
        for (int i = 0; i < iterations; ++i) fn();
        auto end = std::chrono::high_resolution_clock::now();
        samples.push_back(
            std::chrono::duration<double, std::micro>(end - start).count() /
            static_cast<double>(iterations)
        );
    }
    std::sort(samples.begin(), samples.end());
    const size_t middle = samples.size() / 2;
    const double median = (samples.size() % 2 == 0)
        ? (samples[middle - 1] + samples[middle]) * 0.5
        : samples[middle];
    const double mean = std::accumulate(samples.begin(), samples.end(), 0.0) /
        static_cast<double>(samples.size());
    double squared_error = 0.0;
    for (double sample : samples) {
        const double delta = sample - mean;
        squared_error += delta * delta;
    }
    return {
        samples.front(),
        median,
        samples.back(),
        mean,
        std::sqrt(squared_error / static_cast<double>(samples.size())),
    };
}

template <typename T>
flatbuffers::Offset<flatbuffers::Vector<T>> fbVector(flatbuffers::FlatBufferBuilder& builder, const std::vector<T>& values) {
    return builder.CreateVector(values.data(), values.size());
}

flatbuffers::Offset<FbStringVec> fbStringVector(flatbuffers::FlatBufferBuilder& builder, const std::vector<std::string>& values) {
    std::vector<FbStringOffset> offsets;
    offsets.reserve(values.size());
    for (const auto& value : values) offsets.push_back(builder.CreateString(value));
    return builder.CreateVector(offsets);
}

struct CaseDef {
    const char* name;
    const char* reaktor_surface;
    std::function<Bytes()> flex_encode;
    std::function<Bytes()> flat_encode;
    std::function<void(const Bytes&)> flex_partial;
    std::function<void(const Bytes&)> flat_partial;
    std::function<void(const Bytes&)> flex_full;
    std::function<void(const Bytes&)> flat_full;
    size_t schema_bytes_estimate;
};

// 1. Compact scalar model: status strips, counters, selected node identity.
Bytes flexFlatPrimitives() {
    flexbuffers::Builder b(512, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.Bool("f00_active", true);
        b.Int("f01_mode", 7);
        b.Int("f02_nodes", 92);
        b.Int("f03_edges", 144);
        b.Int("f04_warnings", 6);
        b.Double("f05_p95_ms", 36.5);
        b.Double("f06_crash_free", 99.73);
        b.String("f07_branch", "feature/onboarding-auth");
        b.String("f08_target", "Pixel 7 Pro");
    });
    b.Finish();
    return b.GetBuffer();
}

Bytes flatFlatPrimitives() {
    flatbuffers::FlatBufferBuilder b(512);
    auto branch = b.CreateString("feature/onboarding-auth");
    auto target = b.CreateString("Pixel 7 Pro");
    auto start = b.StartTable();
    b.AddElement<uint8_t>(fslot(0), 1, 0);
    b.AddElement<int32_t>(fslot(1), 7, 0);
    b.AddElement<int32_t>(fslot(2), 92, 0);
    b.AddElement<int32_t>(fslot(3), 144, 0);
    b.AddElement<int32_t>(fslot(4), 6, 0);
    b.AddElement<double>(fslot(5), 36.5, 0.0);
    b.AddElement<double>(fslot(6), 99.73, 0.0);
    b.AddOffset(fslot(7), branch);
    b.AddOffset(fslot(8), target);
    return finishFlat(b, endTable(b, start));
}

void scanFlexFlatPrimitives(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    sink(values[0].AsBool());
    sink(values[1].AsInt64());
    sink(values[2].AsInt64());
    sink(values[3].AsInt64());
    sink(values[4].AsInt64());
    sink(values[5].AsDouble());
    sink(values[6].AsDouble());
    sinkFlexString(values[7].AsString());
    sinkFlexString(values[8].AsString());
}

void scanFlatFlatPrimitives(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    sink(root->GetField<uint8_t>(fslot(0), 0) != 0);
    sink(root->GetField<int32_t>(fslot(1), 0));
    sink(root->GetField<int32_t>(fslot(2), 0));
    sink(root->GetField<int32_t>(fslot(3), 0));
    sink(root->GetField<int32_t>(fslot(4), 0));
    sink(root->GetField<double>(fslot(5), 0.0));
    sink(root->GetField<double>(fslot(6), 0.0));
    sinkFbString(root->GetPointer<const FbString*>(fslot(7)));
    sinkFbString(root->GetPointer<const FbString*>(fslot(8)));
}

void fullFlexFlatPrimitives(const Bytes& bytes) {
    scanFlexFlatPrimitives(bytes);
}

void fullFlatFlatPrimitives(const Bytes& bytes) {
    scanFlatFlatPrimitives(bytes);
}

// 2. User/auth profile: string-heavy object with nested settings and tags.
Bytes flexUserProfile() {
    flexbuffers::Builder b(4096, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.String("f00_id", "u_8a3e2c1");
        b.String("f01_handle", "sandra_92");
        b.String("f02_email", "sandra@example.com");
        b.String("f03_display", "Sandra Patel");
        b.Int("f04_created", 1710374400);
        b.Bool("f05_mfa", true);
        b.Map("f06_address", [&] {
            b.String("f00_city", "Bengaluru");
            b.String("f01_country", "IN");
            b.String("f02_tz", "Asia/Kolkata");
            b.Double("f03_lat", 12.9716);
            b.Double("f04_lng", 77.5946);
        });
        b.Vector("f07_tags", [&] {
            for (int i = 0; i < 8; ++i) b.String(token("tag", i).c_str());
        });
        b.Vector("f08_settings", [&] {
            for (int i = 0; i < 12; ++i) b.Bool((i % 3) == 0);
        });
    });
    b.Finish();
    return b.GetBuffer();
}

Bytes flatUserProfile() {
    flatbuffers::FlatBufferBuilder b(4096);
    auto id = b.CreateString("u_8a3e2c1");
    auto handle = b.CreateString("sandra_92");
    auto email = b.CreateString("sandra@example.com");
    auto display = b.CreateString("Sandra Patel");

    auto city = b.CreateString("Bengaluru");
    auto country = b.CreateString("IN");
    auto tz = b.CreateString("Asia/Kolkata");
    auto address_start = b.StartTable();
    b.AddOffset(fslot(0), city);
    b.AddOffset(fslot(1), country);
    b.AddOffset(fslot(2), tz);
    b.AddElement<double>(fslot(3), 12.9716, 0.0);
    b.AddElement<double>(fslot(4), 77.5946, 0.0);
    auto address = endTable(b, address_start);

    std::vector<std::string> tag_strings;
    for (int i = 0; i < 8; ++i) tag_strings.push_back(token("tag", i));
    auto tags = fbStringVector(b, tag_strings);

    std::vector<uint8_t> settings;
    for (int i = 0; i < 12; ++i) settings.push_back((i % 3) == 0 ? 1 : 0);
    auto settings_vec = fbVector<uint8_t>(b, settings);

    auto start = b.StartTable();
    b.AddOffset(fslot(0), id);
    b.AddOffset(fslot(1), handle);
    b.AddOffset(fslot(2), email);
    b.AddOffset(fslot(3), display);
    b.AddElement<int64_t>(fslot(4), 1710374400, 0);
    b.AddElement<uint8_t>(fslot(5), 1, 0);
    b.AddOffset(fslot(6), address);
    b.AddOffset(fslot(7), tags);
    b.AddOffset(fslot(8), settings_vec);
    return finishFlat(b, endTable(b, start));
}

void scanFlexUserProfile(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    sinkFlexString(values[0].AsString());
    sinkFlexString(values[1].AsString());
    sink(values[4].AsInt64());
    sink(values[5].AsBool());
    auto address = values[6].AsMap().Values();
    sinkFlexString(address[0].AsString());
    sink(address[3].AsDouble());
    auto tags = values[7].AsVector();
    for (size_t i = 0; i < tags.size(); i += 3) sinkFlexString(tags[i].AsString());
    auto settings = values[8].AsVector();
    for (size_t i = 0; i < settings.size(); i += 4) sink(settings[i].AsBool());
}

void scanFlatUserProfile(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    sinkFbString(root->GetPointer<const FbString*>(fslot(0)));
    sinkFbString(root->GetPointer<const FbString*>(fslot(1)));
    sink(root->GetField<int64_t>(fslot(4), 0));
    sink(root->GetField<uint8_t>(fslot(5), 0) != 0);
    auto address = root->GetPointer<const FbTable*>(fslot(6));
    sinkFbString(address->GetPointer<const FbString*>(fslot(0)));
    sink(address->GetField<double>(fslot(3), 0.0));
    auto tags = root->GetPointer<const FbStringVec*>(fslot(7));
    for (flatbuffers::uoffset_t i = 0; i < tags->size(); i += 3) sinkFbString(tags->Get(i));
    auto settings = root->GetPointer<const flatbuffers::Vector<uint8_t>*>(fslot(8));
    for (flatbuffers::uoffset_t i = 0; i < settings->size(); i += 4) sink(settings->Get(i) != 0);
}

void fullFlexUserProfile(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    for (int i = 0; i < 4; ++i) sinkFlexString(values[i].AsString());
    sink(values[4].AsInt64());
    sink(values[5].AsBool());
    auto address = values[6].AsMap().Values();
    for (int i = 0; i < 3; ++i) sinkFlexString(address[i].AsString());
    sink(address[3].AsDouble());
    sink(address[4].AsDouble());
    auto tags = values[7].AsVector();
    for (size_t i = 0; i < tags.size(); ++i) sinkFlexString(tags[i].AsString());
    auto settings = values[8].AsVector();
    for (size_t i = 0; i < settings.size(); ++i) sink(settings[i].AsBool());
}

void fullFlatUserProfile(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    for (int i = 0; i < 4; ++i) sinkFbString(root->GetPointer<const FbString*>(fslot(i)));
    sink(root->GetField<int64_t>(fslot(4), 0));
    sink(root->GetField<uint8_t>(fslot(5), 0) != 0);
    auto address = root->GetPointer<const FbTable*>(fslot(6));
    for (int i = 0; i < 3; ++i) sinkFbString(address->GetPointer<const FbString*>(fslot(i)));
    sink(address->GetField<double>(fslot(3), 0.0));
    sink(address->GetField<double>(fslot(4), 0.0));
    auto tags = root->GetPointer<const FbStringVec*>(fslot(7));
    for (flatbuffers::uoffset_t i = 0; i < tags->size(); ++i) sinkFbString(tags->Get(i));
    auto settings = root->GetPointer<const flatbuffers::Vector<uint8_t>*>(fslot(8));
    for (flatbuffers::uoffset_t i = 0; i < settings->size(); ++i) sink(settings->Get(i) != 0);
}

// 3. Database rows: SQL result grids and schema/data panes.
Bytes flexDatabaseRows() {
    flexbuffers::Builder b(32768, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.String("f00_table", "supabase.users");
        b.Vector("f01_rows", [&] {
            for (int i = 0; i < 128; ++i) {
                b.Map([&] {
                    b.String("f00_id", token("u", i).c_str());
                    b.String("f01_handle", token("handle", i).c_str());
                    b.Int("f02_created", 1700000000 + i * 86400);
                    b.Int("f03_messages_24h", (i * 17) % 251);
                    b.Bool("f04_active", (i % 5) != 0);
                });
            }
        });
        b.Vector("f02_columns", [&] {
            b.String("id");
            b.String("handle");
            b.String("created_at");
            b.String("messages_24h");
            b.String("active");
        });
    });
    b.Finish();
    return b.GetBuffer();
}

Bytes flatDatabaseRows() {
    flatbuffers::FlatBufferBuilder b(32768);
    std::vector<FbTableOffset> rows;
    rows.reserve(128);
    for (int i = 0; i < 128; ++i) {
        auto id = b.CreateString(token("u", i));
        auto handle = b.CreateString(token("handle", i));
        auto start = b.StartTable();
        b.AddOffset(fslot(0), id);
        b.AddOffset(fslot(1), handle);
        b.AddElement<int64_t>(fslot(2), 1700000000 + static_cast<int64_t>(i) * 86400, 0);
        b.AddElement<int32_t>(fslot(3), (i * 17) % 251, 0);
        b.AddElement<uint8_t>(fslot(4), (i % 5) != 0 ? 1 : 0, 0);
        rows.push_back(endTable(b, start));
    }
    auto row_vec = b.CreateVector(rows);
    auto columns = fbStringVector(b, {"id", "handle", "created_at", "messages_24h", "active"});
    auto table = b.CreateString("supabase.users");
    auto start = b.StartTable();
    b.AddOffset(fslot(0), table);
    b.AddOffset(fslot(1), row_vec);
    b.AddOffset(fslot(2), columns);
    return finishFlat(b, endTable(b, start));
}

void scanFlexDatabaseRows(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    sinkFlexString(values[0].AsString());
    auto rows = values[1].AsVector();
    for (size_t i = 0; i < rows.size(); i += 7) {
        auto row = rows[i].AsMap().Values();
        sinkFlexString(row[0].AsString());
        sink(row[3].AsInt64());
        sink(row[4].AsBool());
    }
}

void scanFlatDatabaseRows(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    sinkFbString(root->GetPointer<const FbString*>(fslot(0)));
    auto rows = root->GetPointer<const FbTableVec*>(fslot(1));
    for (flatbuffers::uoffset_t i = 0; i < rows->size(); i += 7) {
        auto row = rows->Get(i);
        sinkFbString(row->GetPointer<const FbString*>(fslot(0)));
        sink(row->GetField<int32_t>(fslot(3), 0));
        sink(row->GetField<uint8_t>(fslot(4), 0) != 0);
    }
}

void fullFlexDatabaseRows(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    sinkFlexString(values[0].AsString());
    auto rows = values[1].AsVector();
    for (size_t i = 0; i < rows.size(); ++i) {
        auto row = rows[i].AsMap().Values();
        sinkFlexString(row[0].AsString());
        sinkFlexString(row[1].AsString());
        sink(row[2].AsInt64());
        sink(row[3].AsInt64());
        sink(row[4].AsBool());
    }
    auto columns = values[2].AsVector();
    for (size_t i = 0; i < columns.size(); ++i) sinkFlexString(columns[i].AsString());
}

void fullFlatDatabaseRows(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    sinkFbString(root->GetPointer<const FbString*>(fslot(0)));
    auto rows = root->GetPointer<const FbTableVec*>(fslot(1));
    for (flatbuffers::uoffset_t i = 0; i < rows->size(); ++i) {
        auto row = rows->Get(i);
        sinkFbString(row->GetPointer<const FbString*>(fslot(0)));
        sinkFbString(row->GetPointer<const FbString*>(fslot(1)));
        sink(row->GetField<int64_t>(fslot(2), 0));
        sink(row->GetField<int32_t>(fslot(3), 0));
        sink(row->GetField<uint8_t>(fslot(4), 0) != 0);
    }
    auto columns = root->GetPointer<const FbStringVec*>(fslot(2));
    for (flatbuffers::uoffset_t i = 0; i < columns->size(); ++i) sinkFbString(columns->Get(i));
}

// 4. Graph snapshot: nodes, edges, warnings, inspector data.
Bytes flexGraphSnapshot() {
    flexbuffers::Builder b(65536, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.String("f00_app", "bestbuds");
        b.Vector("f01_nodes", [&] {
            for (int i = 0; i < 96; ++i) {
                b.Map([&] {
                    b.String("f00_id", token("node", i).c_str());
                    b.String("f01_kind", (i % 5 == 0) ? "db" : (i % 5 == 1) ? "screen" : (i % 5 == 2) ? "service" : (i % 5 == 3) ? "agent" : "edge");
                    b.Int("f02_in_ports", i % 4);
                    b.Int("f03_out_ports", (i + 1) % 5);
                    b.Double("f04_p95_ms", 5.0 + (i % 31));
                    b.Bool("f05_warning", (i % 17) == 0);
                });
            }
        });
        b.Vector("f02_edges", [&] {
            for (int i = 0; i < 144; ++i) {
                b.Map([&] {
                    b.Int("f00_from", i % 96);
                    b.Int("f01_to", (i * 7 + 3) % 96);
                    b.String("f02_type", (i % 3 == 0) ? "binding" : (i % 3 == 1) ? "route" : "service");
                    b.Double("f03_weight", 0.5 + (i % 9));
                });
            }
        });
        b.Int("f03_warnings", 6);
    });
    b.Finish();
    return b.GetBuffer();
}

Bytes flatGraphSnapshot() {
    flatbuffers::FlatBufferBuilder b(65536);
    std::vector<FbTableOffset> nodes;
    nodes.reserve(96);
    for (int i = 0; i < 96; ++i) {
        auto id = b.CreateString(token("node", i));
        auto kind = b.CreateString((i % 5 == 0) ? "db" : (i % 5 == 1) ? "screen" : (i % 5 == 2) ? "service" : (i % 5 == 3) ? "agent" : "edge");
        auto start = b.StartTable();
        b.AddOffset(fslot(0), id);
        b.AddOffset(fslot(1), kind);
        b.AddElement<int32_t>(fslot(2), i % 4, 0);
        b.AddElement<int32_t>(fslot(3), (i + 1) % 5, 0);
        b.AddElement<double>(fslot(4), 5.0 + (i % 31), 0.0);
        b.AddElement<uint8_t>(fslot(5), (i % 17) == 0 ? 1 : 0, 0);
        nodes.push_back(endTable(b, start));
    }
    std::vector<FbTableOffset> edges;
    edges.reserve(144);
    for (int i = 0; i < 144; ++i) {
        auto type = b.CreateString((i % 3 == 0) ? "binding" : (i % 3 == 1) ? "route" : "service");
        auto start = b.StartTable();
        b.AddElement<int32_t>(fslot(0), i % 96, 0);
        b.AddElement<int32_t>(fslot(1), (i * 7 + 3) % 96, 0);
        b.AddOffset(fslot(2), type);
        b.AddElement<double>(fslot(3), 0.5 + (i % 9), 0.0);
        edges.push_back(endTable(b, start));
    }
    auto node_vec = b.CreateVector(nodes);
    auto edge_vec = b.CreateVector(edges);
    auto app = b.CreateString("bestbuds");
    auto start = b.StartTable();
    b.AddOffset(fslot(0), app);
    b.AddOffset(fslot(1), node_vec);
    b.AddOffset(fslot(2), edge_vec);
    b.AddElement<int32_t>(fslot(3), 6, 0);
    return finishFlat(b, endTable(b, start));
}

void scanFlexGraphSnapshot(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    auto nodes = values[1].AsVector();
    auto edges = values[2].AsVector();
    for (size_t i = 0; i < nodes.size(); i += 8) {
        auto node = nodes[i].AsMap().Values();
        sinkFlexString(node[0].AsString());
        sink(node[4].AsDouble());
        sink(node[5].AsBool());
    }
    for (size_t i = 0; i < edges.size(); i += 11) {
        auto edge = edges[i].AsMap().Values();
        sink(edge[0].AsInt64());
        sink(edge[1].AsInt64());
    }
}

void scanFlatGraphSnapshot(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    auto nodes = root->GetPointer<const FbTableVec*>(fslot(1));
    auto edges = root->GetPointer<const FbTableVec*>(fslot(2));
    for (flatbuffers::uoffset_t i = 0; i < nodes->size(); i += 8) {
        auto node = nodes->Get(i);
        sinkFbString(node->GetPointer<const FbString*>(fslot(0)));
        sink(node->GetField<double>(fslot(4), 0.0));
        sink(node->GetField<uint8_t>(fslot(5), 0) != 0);
    }
    for (flatbuffers::uoffset_t i = 0; i < edges->size(); i += 11) {
        auto edge = edges->Get(i);
        sink(edge->GetField<int32_t>(fslot(0), 0));
        sink(edge->GetField<int32_t>(fslot(1), 0));
    }
}

void fullFlexGraphSnapshot(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    sinkFlexString(values[0].AsString());
    auto nodes = values[1].AsVector();
    auto edges = values[2].AsVector();
    for (size_t i = 0; i < nodes.size(); ++i) {
        auto node = nodes[i].AsMap().Values();
        sinkFlexString(node[0].AsString());
        sinkFlexString(node[1].AsString());
        sink(node[2].AsInt64());
        sink(node[3].AsInt64());
        sink(node[4].AsDouble());
        sink(node[5].AsBool());
    }
    for (size_t i = 0; i < edges.size(); ++i) {
        auto edge = edges[i].AsMap().Values();
        sink(edge[0].AsInt64());
        sink(edge[1].AsInt64());
        sinkFlexString(edge[2].AsString());
        sink(edge[3].AsDouble());
    }
    sink(values[3].AsInt64());
}

void fullFlatGraphSnapshot(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    sinkFbString(root->GetPointer<const FbString*>(fslot(0)));
    auto nodes = root->GetPointer<const FbTableVec*>(fslot(1));
    auto edges = root->GetPointer<const FbTableVec*>(fslot(2));
    for (flatbuffers::uoffset_t i = 0; i < nodes->size(); ++i) {
        auto node = nodes->Get(i);
        sinkFbString(node->GetPointer<const FbString*>(fslot(0)));
        sinkFbString(node->GetPointer<const FbString*>(fslot(1)));
        sink(node->GetField<int32_t>(fslot(2), 0));
        sink(node->GetField<int32_t>(fslot(3), 0));
        sink(node->GetField<double>(fslot(4), 0.0));
        sink(node->GetField<uint8_t>(fslot(5), 0) != 0);
    }
    for (flatbuffers::uoffset_t i = 0; i < edges->size(); ++i) {
        auto edge = edges->Get(i);
        sink(edge->GetField<int32_t>(fslot(0), 0));
        sink(edge->GetField<int32_t>(fslot(1), 0));
        sinkFbString(edge->GetPointer<const FbString*>(fslot(2)));
        sink(edge->GetField<double>(fslot(3), 0.0));
    }
    sink(root->GetField<int32_t>(fslot(3), 0));
}

// 5. Command queue / code agent: nested command events and status chips.
Bytes flexCommandQueue() {
    flexbuffers::Builder b(32768, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.String("f00_branch", "feature/onboarding-auth");
        b.Vector("f01_commands", [&] {
            for (int i = 0; i < 80; ++i) {
                b.Map([&] {
                    b.String("f00_id", token("cmd", i).c_str());
                    b.Int("f01_lane", i % 6);
                    b.String("f02_target", (i % 4 == 0) ? "UI" : (i % 4 == 1) ? "GRAPH" : (i % 4 == 2) ? "BACKEND" : "TESTS");
                    b.String("f03_status", (i % 5 == 0) ? "agent-planning" : (i % 5 == 1) ? "codediff" : (i % 5 == 2) ? "compiled" : (i % 5 == 3) ? "hot-reloaded" : "queued");
                    b.String("f04_file", token("ui/onboarding/OnboardingScreen.kt", i).c_str());
                    b.Int("f05_additions", i % 9);
                    b.Int("f06_deletions", i % 4);
                });
            }
        });
        b.Int("f02_pending", 7);
    });
    b.Finish();
    return b.GetBuffer();
}

Bytes flatCommandQueue() {
    flatbuffers::FlatBufferBuilder b(32768);
    std::vector<FbTableOffset> commands;
    commands.reserve(80);
    for (int i = 0; i < 80; ++i) {
        auto id = b.CreateString(token("cmd", i));
        auto target = b.CreateString((i % 4 == 0) ? "UI" : (i % 4 == 1) ? "GRAPH" : (i % 4 == 2) ? "BACKEND" : "TESTS");
        auto status = b.CreateString((i % 5 == 0) ? "agent-planning" : (i % 5 == 1) ? "codediff" : (i % 5 == 2) ? "compiled" : (i % 5 == 3) ? "hot-reloaded" : "queued");
        auto file = b.CreateString(token("ui/onboarding/OnboardingScreen.kt", i));
        auto start = b.StartTable();
        b.AddOffset(fslot(0), id);
        b.AddElement<int32_t>(fslot(1), i % 6, 0);
        b.AddOffset(fslot(2), target);
        b.AddOffset(fslot(3), status);
        b.AddOffset(fslot(4), file);
        b.AddElement<int32_t>(fslot(5), i % 9, 0);
        b.AddElement<int32_t>(fslot(6), i % 4, 0);
        commands.push_back(endTable(b, start));
    }
    auto command_vec = b.CreateVector(commands);
    auto branch = b.CreateString("feature/onboarding-auth");
    auto start = b.StartTable();
    b.AddOffset(fslot(0), branch);
    b.AddOffset(fslot(1), command_vec);
    b.AddElement<int32_t>(fslot(2), 7, 0);
    return finishFlat(b, endTable(b, start));
}

void scanFlexCommandQueue(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    sinkFlexString(values[0].AsString());
    auto commands = values[1].AsVector();
    for (size_t i = 0; i < commands.size(); i += 6) {
        auto command = commands[i].AsMap().Values();
        sinkFlexString(command[2].AsString());
        sinkFlexString(command[3].AsString());
        sink(command[5].AsInt64() - command[6].AsInt64());
    }
    sink(values[2].AsInt64());
}

void scanFlatCommandQueue(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    sinkFbString(root->GetPointer<const FbString*>(fslot(0)));
    auto commands = root->GetPointer<const FbTableVec*>(fslot(1));
    for (flatbuffers::uoffset_t i = 0; i < commands->size(); i += 6) {
        auto command = commands->Get(i);
        sinkFbString(command->GetPointer<const FbString*>(fslot(2)));
        sinkFbString(command->GetPointer<const FbString*>(fslot(3)));
        sink(command->GetField<int32_t>(fslot(5), 0) - command->GetField<int32_t>(fslot(6), 0));
    }
    sink(root->GetField<int32_t>(fslot(2), 0));
}

void fullFlexCommandQueue(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    sinkFlexString(values[0].AsString());
    auto commands = values[1].AsVector();
    for (size_t i = 0; i < commands.size(); ++i) {
        auto command = commands[i].AsMap().Values();
        sinkFlexString(command[0].AsString());
        sink(command[1].AsInt64());
        sinkFlexString(command[2].AsString());
        sinkFlexString(command[3].AsString());
        sinkFlexString(command[4].AsString());
        sink(command[5].AsInt64());
        sink(command[6].AsInt64());
    }
    sink(values[2].AsInt64());
}

void fullFlatCommandQueue(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    sinkFbString(root->GetPointer<const FbString*>(fslot(0)));
    auto commands = root->GetPointer<const FbTableVec*>(fslot(1));
    for (flatbuffers::uoffset_t i = 0; i < commands->size(); ++i) {
        auto command = commands->Get(i);
        sinkFbString(command->GetPointer<const FbString*>(fslot(0)));
        sink(command->GetField<int32_t>(fslot(1), 0));
        sinkFbString(command->GetPointer<const FbString*>(fslot(2)));
        sinkFbString(command->GetPointer<const FbString*>(fslot(3)));
        sinkFbString(command->GetPointer<const FbString*>(fslot(4)));
        sink(command->GetField<int32_t>(fslot(5), 0));
        sink(command->GetField<int32_t>(fslot(6), 0));
    }
    sink(root->GetField<int32_t>(fslot(2), 0));
}

// 6. Agent/AI trace: model calls, token/cost counters, MCP context rows.
Bytes flexAgentTrace() {
    flexbuffers::Builder b(49152, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.String("f00_conversation", "conv_auth_migration");
        b.Vector("f01_events", [&] {
            for (int i = 0; i < 120; ++i) {
                b.Map([&] {
                    b.Int("f00_ts", 1716650000 + i * 9);
                    b.String("f01_lane", (i % 4 == 0) ? "codex" : (i % 4 == 1) ? "claude" : (i % 4 == 2) ? "gemini" : "scout");
                    b.String("f02_model", (i % 3 == 0) ? "claude-sonnet" : (i % 3 == 1) ? "gemini-1.5-pro" : "gpt-4.1");
                    b.Int("f03_tokens_in", 800 + i * 11);
                    b.Int("f04_tokens_out", 120 + i * 5);
                    b.Double("f05_cost", 0.001 * (i + 1));
                    b.String("f06_state", (i % 5 == 0) ? "planning" : "running");
                });
            }
        });
        b.Vector("f02_context_files", [&] {
            for (int i = 0; i < 24; ++i) b.String(token("modules/engine/panel", i).c_str());
        });
    });
    b.Finish();
    return b.GetBuffer();
}

Bytes flatAgentTrace() {
    flatbuffers::FlatBufferBuilder b(49152);
    std::vector<FbTableOffset> events;
    events.reserve(120);
    for (int i = 0; i < 120; ++i) {
        auto lane = b.CreateString((i % 4 == 0) ? "codex" : (i % 4 == 1) ? "claude" : (i % 4 == 2) ? "gemini" : "scout");
        auto model = b.CreateString((i % 3 == 0) ? "claude-sonnet" : (i % 3 == 1) ? "gemini-1.5-pro" : "gpt-4.1");
        auto state = b.CreateString((i % 5 == 0) ? "planning" : "running");
        auto start = b.StartTable();
        b.AddElement<int64_t>(fslot(0), 1716650000 + static_cast<int64_t>(i) * 9, 0);
        b.AddOffset(fslot(1), lane);
        b.AddOffset(fslot(2), model);
        b.AddElement<int32_t>(fslot(3), 800 + i * 11, 0);
        b.AddElement<int32_t>(fslot(4), 120 + i * 5, 0);
        b.AddElement<double>(fslot(5), 0.001 * (i + 1), 0.0);
        b.AddOffset(fslot(6), state);
        events.push_back(endTable(b, start));
    }
    std::vector<std::string> files;
    for (int i = 0; i < 24; ++i) files.push_back(token("modules/engine/panel", i));
    auto event_vec = b.CreateVector(events);
    auto file_vec = fbStringVector(b, files);
    auto conversation = b.CreateString("conv_auth_migration");
    auto start = b.StartTable();
    b.AddOffset(fslot(0), conversation);
    b.AddOffset(fslot(1), event_vec);
    b.AddOffset(fslot(2), file_vec);
    return finishFlat(b, endTable(b, start));
}

void scanFlexAgentTrace(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    auto events = values[1].AsVector();
    for (size_t i = 0; i < events.size(); i += 9) {
        auto event = events[i].AsMap().Values();
        sinkFlexString(event[1].AsString());
        sinkFlexString(event[2].AsString());
        sink(event[3].AsInt64() + event[4].AsInt64());
        sink(event[5].AsDouble());
    }
    auto files = values[2].AsVector();
    for (size_t i = 0; i < files.size(); i += 5) sinkFlexString(files[i].AsString());
}

void scanFlatAgentTrace(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    auto events = root->GetPointer<const FbTableVec*>(fslot(1));
    for (flatbuffers::uoffset_t i = 0; i < events->size(); i += 9) {
        auto event = events->Get(i);
        sinkFbString(event->GetPointer<const FbString*>(fslot(1)));
        sinkFbString(event->GetPointer<const FbString*>(fslot(2)));
        sink(event->GetField<int32_t>(fslot(3), 0) + event->GetField<int32_t>(fslot(4), 0));
        sink(event->GetField<double>(fslot(5), 0.0));
    }
    auto files = root->GetPointer<const FbStringVec*>(fslot(2));
    for (flatbuffers::uoffset_t i = 0; i < files->size(); i += 5) sinkFbString(files->Get(i));
}

void fullFlexAgentTrace(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    sinkFlexString(values[0].AsString());
    auto events = values[1].AsVector();
    for (size_t i = 0; i < events.size(); ++i) {
        auto event = events[i].AsMap().Values();
        sink(event[0].AsInt64());
        sinkFlexString(event[1].AsString());
        sinkFlexString(event[2].AsString());
        sink(event[3].AsInt64());
        sink(event[4].AsInt64());
        sink(event[5].AsDouble());
        sinkFlexString(event[6].AsString());
    }
    auto files = values[2].AsVector();
    for (size_t i = 0; i < files.size(); ++i) sinkFlexString(files[i].AsString());
}

void fullFlatAgentTrace(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    sinkFbString(root->GetPointer<const FbString*>(fslot(0)));
    auto events = root->GetPointer<const FbTableVec*>(fslot(1));
    for (flatbuffers::uoffset_t i = 0; i < events->size(); ++i) {
        auto event = events->Get(i);
        sink(event->GetField<int64_t>(fslot(0), 0));
        sinkFbString(event->GetPointer<const FbString*>(fslot(1)));
        sinkFbString(event->GetPointer<const FbString*>(fslot(2)));
        sink(event->GetField<int32_t>(fslot(3), 0));
        sink(event->GetField<int32_t>(fslot(4), 0));
        sink(event->GetField<double>(fslot(5), 0.0));
        sinkFbString(event->GetPointer<const FbString*>(fslot(6)));
    }
    auto files = root->GetPointer<const FbStringVec*>(fslot(2));
    for (flatbuffers::uoffset_t i = 0; i < files->size(); ++i) sinkFbString(files->Get(i));
}

// 7. Time series / traces / metrics: dense numeric vectors.
Bytes flexTimeSeries() {
    std::vector<int64_t> ts(512);
    std::vector<double> values(512);
    for (int i = 0; i < 512; ++i) {
        ts[i] = 1716650000000LL + i * 1000;
        values[i] = 50.0 + (i % 37) * 0.25;
    }
    flexbuffers::Builder b(16384, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.String("f00_name", "latency_p95");
        b.Int("f01_start", ts.front());
        b.Int("f02_interval_ms", 1000);
        b.Vector("f03_timestamps", ts.data(), ts.size());
        b.Vector("f04_values", values.data(), values.size());
        b.String("f05_unit", "ms");
    });
    b.Finish();
    return b.GetBuffer();
}

Bytes flatTimeSeries() {
    std::vector<int64_t> ts(512);
    std::vector<double> values(512);
    for (int i = 0; i < 512; ++i) {
        ts[i] = 1716650000000LL + i * 1000;
        values[i] = 50.0 + (i % 37) * 0.25;
    }
    flatbuffers::FlatBufferBuilder b(16384);
    auto name = b.CreateString("latency_p95");
    auto unit = b.CreateString("ms");
    auto ts_vec = fbVector<int64_t>(b, ts);
    auto value_vec = fbVector<double>(b, values);
    auto start = b.StartTable();
    b.AddOffset(fslot(0), name);
    b.AddElement<int64_t>(fslot(1), ts.front(), 0);
    b.AddElement<int32_t>(fslot(2), 1000, 0);
    b.AddOffset(fslot(3), ts_vec);
    b.AddOffset(fslot(4), value_vec);
    b.AddOffset(fslot(5), unit);
    return finishFlat(b, endTable(b, start));
}

void scanFlexTimeSeries(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    auto ts = values[3].AsTypedVector();
    auto samples = values[4].AsTypedVector();
    for (size_t i = 0; i < ts.size(); i += 16) {
        sink(ts[i].AsInt64());
        sink(samples[i].AsDouble());
    }
}

void scanFlatTimeSeries(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    auto ts = root->GetPointer<const flatbuffers::Vector<int64_t>*>(fslot(3));
    auto samples = root->GetPointer<const flatbuffers::Vector<double>*>(fslot(4));
    for (flatbuffers::uoffset_t i = 0; i < ts->size(); i += 16) {
        sink(ts->Get(i));
        sink(samples->Get(i));
    }
}

void fullFlexTimeSeries(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    sinkFlexString(values[0].AsString());
    sink(values[1].AsInt64());
    sink(values[2].AsInt64());
    auto ts = values[3].AsTypedVector();
    auto samples = values[4].AsTypedVector();
    for (size_t i = 0; i < ts.size(); ++i) {
        sink(ts[i].AsInt64());
        sink(samples[i].AsDouble());
    }
    sinkFlexString(values[5].AsString());
}

void fullFlatTimeSeries(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    sinkFbString(root->GetPointer<const FbString*>(fslot(0)));
    sink(root->GetField<int64_t>(fslot(1), 0));
    sink(root->GetField<int32_t>(fslot(2), 0));
    auto ts = root->GetPointer<const flatbuffers::Vector<int64_t>*>(fslot(3));
    auto samples = root->GetPointer<const flatbuffers::Vector<double>*>(fslot(4));
    for (flatbuffers::uoffset_t i = 0; i < ts->size(); ++i) {
        sink(ts->Get(i));
        sink(samples->Get(i));
    }
    sinkFbString(root->GetPointer<const FbString*>(fslot(5)));
}

// 8. Deploy/insights: partitions, checks, counters and status rows.
Bytes flexDeployInsights() {
    flexbuffers::Builder b(49152, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.String("f00_release", "staging-1042");
        b.Vector("f01_partitions", [&] {
            const char* names[] = {"WEB", "WORKERS", "DATABASE", "DESKTOP", "MOBILE", "SERVER"};
            for (int i = 0; i < 6; ++i) {
                b.Map([&] {
                    b.String("f00_name", names[i]);
                    b.Int("f01_status", i % 4);
                    b.Double("f02_p95_ms", 40.0 + i * 11.0);
                    b.Int("f03_errors", i == 2 ? 3 : 0);
                    b.Vector("f04_checks", [&] {
                        for (int j = 0; j < 14; ++j) {
                            b.Map([&] {
                                b.String("f00_id", token("check", i * 100 + j).c_str());
                                b.Int("f01_status", (j + i) % 5);
                                b.Int("f02_duration_ms", 120 + j * 13);
                            });
                        }
                    });
                });
            }
        });
        b.Double("f02_cost_usd", 42.75);
        b.Int("f03_active_users", 81242);
    });
    b.Finish();
    return b.GetBuffer();
}

Bytes flatDeployInsights() {
    flatbuffers::FlatBufferBuilder b(49152);
    std::vector<FbTableOffset> partitions;
    const char* names[] = {"WEB", "WORKERS", "DATABASE", "DESKTOP", "MOBILE", "SERVER"};
    for (int i = 0; i < 6; ++i) {
        std::vector<FbTableOffset> checks;
        for (int j = 0; j < 14; ++j) {
            auto id = b.CreateString(token("check", i * 100 + j));
            auto start = b.StartTable();
            b.AddOffset(fslot(0), id);
            b.AddElement<int32_t>(fslot(1), (j + i) % 5, 0);
            b.AddElement<int32_t>(fslot(2), 120 + j * 13, 0);
            checks.push_back(endTable(b, start));
        }
        auto check_vec = b.CreateVector(checks);
        auto name = b.CreateString(names[i]);
        auto start = b.StartTable();
        b.AddOffset(fslot(0), name);
        b.AddElement<int32_t>(fslot(1), i % 4, 0);
        b.AddElement<double>(fslot(2), 40.0 + i * 11.0, 0.0);
        b.AddElement<int32_t>(fslot(3), i == 2 ? 3 : 0, 0);
        b.AddOffset(fslot(4), check_vec);
        partitions.push_back(endTable(b, start));
    }
    auto partition_vec = b.CreateVector(partitions);
    auto release = b.CreateString("staging-1042");
    auto start = b.StartTable();
    b.AddOffset(fslot(0), release);
    b.AddOffset(fslot(1), partition_vec);
    b.AddElement<double>(fslot(2), 42.75, 0.0);
    b.AddElement<int32_t>(fslot(3), 81242, 0);
    return finishFlat(b, endTable(b, start));
}

void scanFlexDeployInsights(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    auto partitions = values[1].AsVector();
    for (size_t i = 0; i < partitions.size(); ++i) {
        auto partition = partitions[i].AsMap().Values();
        sinkFlexString(partition[0].AsString());
        sink(partition[2].AsDouble());
        auto checks = partition[4].AsVector();
        for (size_t j = 0; j < checks.size(); j += 5) {
            auto check = checks[j].AsMap().Values();
            sink(check[1].AsInt64());
            sink(check[2].AsInt64());
        }
    }
    sink(values[3].AsInt64());
}

void scanFlatDeployInsights(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    auto partitions = root->GetPointer<const FbTableVec*>(fslot(1));
    for (flatbuffers::uoffset_t i = 0; i < partitions->size(); ++i) {
        auto partition = partitions->Get(i);
        sinkFbString(partition->GetPointer<const FbString*>(fslot(0)));
        sink(partition->GetField<double>(fslot(2), 0.0));
        auto checks = partition->GetPointer<const FbTableVec*>(fslot(4));
        for (flatbuffers::uoffset_t j = 0; j < checks->size(); j += 5) {
            auto check = checks->Get(j);
            sink(check->GetField<int32_t>(fslot(1), 0));
            sink(check->GetField<int32_t>(fslot(2), 0));
        }
    }
    sink(root->GetField<int32_t>(fslot(3), 0));
}

void fullFlexDeployInsights(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    sinkFlexString(values[0].AsString());
    auto partitions = values[1].AsVector();
    for (size_t i = 0; i < partitions.size(); ++i) {
        auto partition = partitions[i].AsMap().Values();
        sinkFlexString(partition[0].AsString());
        sink(partition[1].AsInt64());
        sink(partition[2].AsDouble());
        sink(partition[3].AsInt64());
        auto checks = partition[4].AsVector();
        for (size_t j = 0; j < checks.size(); ++j) {
            auto check = checks[j].AsMap().Values();
            sinkFlexString(check[0].AsString());
            sink(check[1].AsInt64());
            sink(check[2].AsInt64());
        }
    }
    sink(values[2].AsDouble());
    sink(values[3].AsInt64());
}

void fullFlatDeployInsights(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    sinkFbString(root->GetPointer<const FbString*>(fslot(0)));
    auto partitions = root->GetPointer<const FbTableVec*>(fslot(1));
    for (flatbuffers::uoffset_t i = 0; i < partitions->size(); ++i) {
        auto partition = partitions->Get(i);
        sinkFbString(partition->GetPointer<const FbString*>(fslot(0)));
        sink(partition->GetField<int32_t>(fslot(1), 0));
        sink(partition->GetField<double>(fslot(2), 0.0));
        sink(partition->GetField<int32_t>(fslot(3), 0));
        auto checks = partition->GetPointer<const FbTableVec*>(fslot(4));
        for (flatbuffers::uoffset_t j = 0; j < checks->size(); ++j) {
            auto check = checks->Get(j);
            sinkFbString(check->GetPointer<const FbString*>(fslot(0)));
            sink(check->GetField<int32_t>(fslot(1), 0));
            sink(check->GetField<int32_t>(fslot(2), 0));
        }
    }
    sink(root->GetField<double>(fslot(2), 0.0));
    sink(root->GetField<int32_t>(fslot(3), 0));
}

// 9. Auth/RBAC: permission matrix, policy rows, sessions and audit surfaces.
Bytes flexAuthPermissionMatrix() {
    flexbuffers::Builder b(65536, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.String("f00_app", "bestbuds");
        b.Vector("f01_roles", [&] {
            for (int i = 0; i < 12; ++i) {
                b.Map([&] {
                    b.String("f00_id", token("role", i).c_str());
                    b.String("f01_name", (i == 0) ? "admin" : (i == 1) ? "moderator" : token("role_name", i).c_str());
                    b.Int("f02_level", i);
                    b.Bool("f03_managed", i < 3);
                });
            }
        });
        b.Vector("f02_permissions", [&] {
            for (int i = 0; i < 64; ++i) {
                b.Map([&] {
                    b.String("f00_id", token("perm", i).c_str());
                    b.String("f01_category", (i % 4 == 0) ? "event" : (i % 4 == 1) ? "payment" : (i % 4 == 2) ? "admin" : "agent");
                    b.String("f02_action", (i % 3 == 0) ? "read" : (i % 3 == 1) ? "write" : "execute");
                    b.Int("f03_risk", i % 5);
                });
            }
        });
        b.Vector("f03_policies", [&] {
            for (int i = 0; i < 256; ++i) {
                b.Map([&] {
                    b.Int("f00_role", i % 12);
                    b.Int("f01_permission", (i * 7) % 64);
                    b.Bool("f02_allow", (i % 9) != 0);
                    b.String("f03_condition", (i % 5 == 0) ? "mfa && device_trusted" : "default");
                });
            }
        });
        b.Vector("f04_sessions", [&] {
            for (int i = 0; i < 64; ++i) {
                b.Map([&] {
                    b.String("f00_id", token("sess", i).c_str());
                    b.String("f01_provider", (i % 3 == 0) ? "apple" : (i % 3 == 1) ? "google" : "email");
                    b.Int("f02_age_s", i * 271);
                    b.Bool("f03_mfa", (i % 4) == 0);
                });
            }
        });
    });
    b.Finish();
    return b.GetBuffer();
}

Bytes flatAuthPermissionMatrix() {
    flatbuffers::FlatBufferBuilder b(65536);
    std::vector<FbTableOffset> roles;
    for (int i = 0; i < 12; ++i) {
        auto id = b.CreateString(token("role", i));
        auto name = b.CreateString((i == 0) ? "admin" : (i == 1) ? "moderator" : token("role_name", i));
        auto start = b.StartTable();
        b.AddOffset(fslot(0), id);
        b.AddOffset(fslot(1), name);
        b.AddElement<int32_t>(fslot(2), i, 0);
        b.AddElement<uint8_t>(fslot(3), i < 3 ? 1 : 0, 0);
        roles.push_back(endTable(b, start));
    }
    std::vector<FbTableOffset> permissions;
    for (int i = 0; i < 64; ++i) {
        auto id = b.CreateString(token("perm", i));
        auto category = b.CreateString((i % 4 == 0) ? "event" : (i % 4 == 1) ? "payment" : (i % 4 == 2) ? "admin" : "agent");
        auto action = b.CreateString((i % 3 == 0) ? "read" : (i % 3 == 1) ? "write" : "execute");
        auto start = b.StartTable();
        b.AddOffset(fslot(0), id);
        b.AddOffset(fslot(1), category);
        b.AddOffset(fslot(2), action);
        b.AddElement<int32_t>(fslot(3), i % 5, 0);
        permissions.push_back(endTable(b, start));
    }
    std::vector<FbTableOffset> policies;
    for (int i = 0; i < 256; ++i) {
        auto condition = b.CreateString((i % 5 == 0) ? "mfa && device_trusted" : "default");
        auto start = b.StartTable();
        b.AddElement<int32_t>(fslot(0), i % 12, 0);
        b.AddElement<int32_t>(fslot(1), (i * 7) % 64, 0);
        b.AddElement<uint8_t>(fslot(2), (i % 9) != 0 ? 1 : 0, 0);
        b.AddOffset(fslot(3), condition);
        policies.push_back(endTable(b, start));
    }
    std::vector<FbTableOffset> sessions;
    for (int i = 0; i < 64; ++i) {
        auto id = b.CreateString(token("sess", i));
        auto provider = b.CreateString((i % 3 == 0) ? "apple" : (i % 3 == 1) ? "google" : "email");
        auto start = b.StartTable();
        b.AddOffset(fslot(0), id);
        b.AddOffset(fslot(1), provider);
        b.AddElement<int32_t>(fslot(2), i * 271, 0);
        b.AddElement<uint8_t>(fslot(3), (i % 4) == 0 ? 1 : 0, 0);
        sessions.push_back(endTable(b, start));
    }
    auto app = b.CreateString("bestbuds");
    auto role_vec = b.CreateVector(roles);
    auto perm_vec = b.CreateVector(permissions);
    auto policy_vec = b.CreateVector(policies);
    auto session_vec = b.CreateVector(sessions);
    auto start = b.StartTable();
    b.AddOffset(fslot(0), app);
    b.AddOffset(fslot(1), role_vec);
    b.AddOffset(fslot(2), perm_vec);
    b.AddOffset(fslot(3), policy_vec);
    b.AddOffset(fslot(4), session_vec);
    return finishFlat(b, endTable(b, start));
}

void scanFlexAuthPermissionMatrix(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    auto policies = values[3].AsVector();
    for (size_t i = 0; i < policies.size(); i += 23) {
        auto policy = policies[i].AsMap().Values();
        sink(policy[0].AsInt64());
        sink(policy[1].AsInt64());
        sink(policy[2].AsBool());
    }
    auto sessions = values[4].AsVector();
    for (size_t i = 0; i < sessions.size(); i += 13) {
        auto session = sessions[i].AsMap().Values();
        sinkFlexString(session[1].AsString());
        sink(session[3].AsBool());
    }
}

void scanFlatAuthPermissionMatrix(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    auto policies = root->GetPointer<const FbTableVec*>(fslot(3));
    for (flatbuffers::uoffset_t i = 0; i < policies->size(); i += 23) {
        auto policy = policies->Get(i);
        sink(policy->GetField<int32_t>(fslot(0), 0));
        sink(policy->GetField<int32_t>(fslot(1), 0));
        sink(policy->GetField<uint8_t>(fslot(2), 0) != 0);
    }
    auto sessions = root->GetPointer<const FbTableVec*>(fslot(4));
    for (flatbuffers::uoffset_t i = 0; i < sessions->size(); i += 13) {
        auto session = sessions->Get(i);
        sinkFbString(session->GetPointer<const FbString*>(fslot(1)));
        sink(session->GetField<uint8_t>(fslot(3), 0) != 0);
    }
}

void fullFlexAuthPermissionMatrix(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    sinkFlexString(values[0].AsString());
    for (size_t section = 1; section <= 4; ++section) {
        auto rows = values[section].AsVector();
        for (size_t i = 0; i < rows.size(); ++i) {
            auto row = rows[i].AsMap().Values();
            for (size_t j = 0; j < row.size(); ++j) {
                if (row[j].IsString()) sinkFlexString(row[j].AsString());
                else if (row[j].IsBool()) sink(row[j].AsBool());
                else sink(row[j].AsInt64());
            }
        }
    }
}

void fullFlatAuthPermissionMatrix(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    sinkFbString(root->GetPointer<const FbString*>(fslot(0)));
    auto roles = root->GetPointer<const FbTableVec*>(fslot(1));
    for (flatbuffers::uoffset_t i = 0; i < roles->size(); ++i) {
        auto row = roles->Get(i);
        sinkFbString(row->GetPointer<const FbString*>(fslot(0)));
        sinkFbString(row->GetPointer<const FbString*>(fslot(1)));
        sink(row->GetField<int32_t>(fslot(2), 0));
        sink(row->GetField<uint8_t>(fslot(3), 0) != 0);
    }
    auto permissions = root->GetPointer<const FbTableVec*>(fslot(2));
    for (flatbuffers::uoffset_t i = 0; i < permissions->size(); ++i) {
        auto row = permissions->Get(i);
        sinkFbString(row->GetPointer<const FbString*>(fslot(0)));
        sinkFbString(row->GetPointer<const FbString*>(fslot(1)));
        sinkFbString(row->GetPointer<const FbString*>(fslot(2)));
        sink(row->GetField<int32_t>(fslot(3), 0));
    }
    auto policies = root->GetPointer<const FbTableVec*>(fslot(3));
    for (flatbuffers::uoffset_t i = 0; i < policies->size(); ++i) {
        auto row = policies->Get(i);
        sink(row->GetField<int32_t>(fslot(0), 0));
        sink(row->GetField<int32_t>(fslot(1), 0));
        sink(row->GetField<uint8_t>(fslot(2), 0) != 0);
        sinkFbString(row->GetPointer<const FbString*>(fslot(3)));
    }
    auto sessions = root->GetPointer<const FbTableVec*>(fslot(4));
    for (flatbuffers::uoffset_t i = 0; i < sessions->size(); ++i) {
        auto row = sessions->Get(i);
        sinkFbString(row->GetPointer<const FbString*>(fslot(0)));
        sinkFbString(row->GetPointer<const FbString*>(fslot(1)));
        sink(row->GetField<int32_t>(fslot(2), 0));
        sink(row->GetField<uint8_t>(fslot(3), 0) != 0);
    }
}

// 10. Testing pane: suite/test matrix, devices, replay/proxy metadata.
Bytes flexTestingMatrix() {
    flexbuffers::Builder b(65536, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.Int("f00_pass", 214);
        b.Int("f01_fail", 7);
        b.Int("f02_flaky", 11);
        b.Int("f03_skip", 8);
        b.Vector("f04_tests", [&] {
            for (int i = 0; i < 240; ++i) {
                b.Map([&] {
                    b.String("f00_suite", (i % 5 == 0) ? "auth-login" : (i % 5 == 1) ? "db-query" : (i % 5 == 2) ? "graph-flow" : (i % 5 == 3) ? "agent-pipeline" : "deploy");
                    b.String("f01_name", token("test", i).c_str());
                    b.Int("f02_status", i % 17 == 0 ? 1 : i % 19 == 0 ? 2 : i % 23 == 0 ? 3 : 0);
                    b.Int("f03_duration_ms", 80 + (i * 37) % 900);
                    b.String("f04_device", (i % 3 == 0) ? "Pixel 7 Pro" : (i % 3 == 1) ? "iPhone 16" : "Chrome");
                    b.Bool("f05_replay", (i % 4) == 0);
                    b.Bool("f06_proxy", (i % 6) == 0);
                    b.Int("f07_trace_count", i % 9);
                });
            }
        });
    });
    b.Finish();
    return b.GetBuffer();
}

Bytes flatTestingMatrix() {
    flatbuffers::FlatBufferBuilder b(65536);
    std::vector<FbTableOffset> tests;
    for (int i = 0; i < 240; ++i) {
        auto suite = b.CreateString((i % 5 == 0) ? "auth-login" : (i % 5 == 1) ? "db-query" : (i % 5 == 2) ? "graph-flow" : (i % 5 == 3) ? "agent-pipeline" : "deploy");
        auto name = b.CreateString(token("test", i));
        auto device = b.CreateString((i % 3 == 0) ? "Pixel 7 Pro" : (i % 3 == 1) ? "iPhone 16" : "Chrome");
        auto start = b.StartTable();
        b.AddOffset(fslot(0), suite);
        b.AddOffset(fslot(1), name);
        b.AddElement<int32_t>(fslot(2), i % 17 == 0 ? 1 : i % 19 == 0 ? 2 : i % 23 == 0 ? 3 : 0, 0);
        b.AddElement<int32_t>(fslot(3), 80 + (i * 37) % 900, 0);
        b.AddOffset(fslot(4), device);
        b.AddElement<uint8_t>(fslot(5), (i % 4) == 0 ? 1 : 0, 0);
        b.AddElement<uint8_t>(fslot(6), (i % 6) == 0 ? 1 : 0, 0);
        b.AddElement<int32_t>(fslot(7), i % 9, 0);
        tests.push_back(endTable(b, start));
    }
    auto test_vec = b.CreateVector(tests);
    auto start = b.StartTable();
    b.AddElement<int32_t>(fslot(0), 214, 0);
    b.AddElement<int32_t>(fslot(1), 7, 0);
    b.AddElement<int32_t>(fslot(2), 11, 0);
    b.AddElement<int32_t>(fslot(3), 8, 0);
    b.AddOffset(fslot(4), test_vec);
    return finishFlat(b, endTable(b, start));
}

void scanFlexTestingMatrix(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    auto tests = values[4].AsVector();
    for (size_t i = 0; i < tests.size(); i += 29) {
        auto test = tests[i].AsMap().Values();
        sinkFlexString(test[0].AsString());
        sink(test[2].AsInt64());
        sink(test[3].AsInt64());
        sink(test[5].AsBool());
    }
}

void scanFlatTestingMatrix(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    auto tests = root->GetPointer<const FbTableVec*>(fslot(4));
    for (flatbuffers::uoffset_t i = 0; i < tests->size(); i += 29) {
        auto test = tests->Get(i);
        sinkFbString(test->GetPointer<const FbString*>(fslot(0)));
        sink(test->GetField<int32_t>(fslot(2), 0));
        sink(test->GetField<int32_t>(fslot(3), 0));
        sink(test->GetField<uint8_t>(fslot(5), 0) != 0);
    }
}

void fullFlexTestingMatrix(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    for (int i = 0; i < 4; ++i) sink(values[i].AsInt64());
    auto tests = values[4].AsVector();
    for (size_t i = 0; i < tests.size(); ++i) {
        auto test = tests[i].AsMap().Values();
        sinkFlexString(test[0].AsString());
        sinkFlexString(test[1].AsString());
        sink(test[2].AsInt64());
        sink(test[3].AsInt64());
        sinkFlexString(test[4].AsString());
        sink(test[5].AsBool());
        sink(test[6].AsBool());
        sink(test[7].AsInt64());
    }
}

void fullFlatTestingMatrix(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    for (int i = 0; i < 4; ++i) sink(root->GetField<int32_t>(fslot(i), 0));
    auto tests = root->GetPointer<const FbTableVec*>(fslot(4));
    for (flatbuffers::uoffset_t i = 0; i < tests->size(); ++i) {
        auto test = tests->Get(i);
        sinkFbString(test->GetPointer<const FbString*>(fslot(0)));
        sinkFbString(test->GetPointer<const FbString*>(fslot(1)));
        sink(test->GetField<int32_t>(fslot(2), 0));
        sink(test->GetField<int32_t>(fslot(3), 0));
        sinkFbString(test->GetPointer<const FbString*>(fslot(4)));
        sink(test->GetField<uint8_t>(fslot(5), 0) != 0);
        sink(test->GetField<uint8_t>(fslot(6), 0) != 0);
        sink(test->GetField<int32_t>(fslot(7), 0));
    }
}

// 11. AI registry: model rows, gateway routes, cost/eval metadata.
Bytes flexAiRegistry() {
    flexbuffers::Builder b(49152, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.Vector("f00_models", [&] {
            for (int i = 0; i < 72; ++i) {
                b.Map([&] {
                    b.String("f00_name", (i % 6 == 0) ? "Whisper Tiny" : (i % 6 == 1) ? "Style Transfer" : (i % 6 == 2) ? "Recommendation Engine" : (i % 6 == 3) ? "Content Moderation" : (i % 6 == 4) ? "Claude Sonnet" : "Gemini");
                    b.String("f01_platform", (i % 3 == 0) ? "ExecuTorch" : (i % 3 == 1) ? "PyTorch/FastAPI" : "CF AI Gateway");
                    b.String("f02_route", token("/ai/model", i).c_str());
                    b.Int("f03_latency_ms", 18 + (i * 13) % 240);
                    b.Int("f04_calls", 1000 + i * 431);
                    b.Double("f05_accuracy", 0.72 + (i % 25) * 0.01);
                    b.Double("f06_cost", 0.0007 * (i + 1));
                    b.Int("f07_size_mb", 12 + i * 7);
                    b.String("f08_owner", (i % 2 == 0) ? "@ai-platform" : "@auth-squad");
                });
            }
        });
        b.Vector("f01_evals", [&] {
            for (int i = 0; i < 48; ++i) {
                b.Map([&] {
                    b.String("f00_suite", token("eval", i).c_str());
                    b.Int("f01_pass", 80 + i % 17);
                    b.Int("f02_fail", i % 5);
                    b.Double("f03_score", 0.8 + (i % 13) * 0.01);
                });
            }
        });
    });
    b.Finish();
    return b.GetBuffer();
}

Bytes flatAiRegistry() {
    flatbuffers::FlatBufferBuilder b(49152);
    std::vector<FbTableOffset> models;
    for (int i = 0; i < 72; ++i) {
        auto name = b.CreateString((i % 6 == 0) ? "Whisper Tiny" : (i % 6 == 1) ? "Style Transfer" : (i % 6 == 2) ? "Recommendation Engine" : (i % 6 == 3) ? "Content Moderation" : (i % 6 == 4) ? "Claude Sonnet" : "Gemini");
        auto platform = b.CreateString((i % 3 == 0) ? "ExecuTorch" : (i % 3 == 1) ? "PyTorch/FastAPI" : "CF AI Gateway");
        auto route = b.CreateString(token("/ai/model", i));
        auto owner = b.CreateString((i % 2 == 0) ? "@ai-platform" : "@auth-squad");
        auto start = b.StartTable();
        b.AddOffset(fslot(0), name);
        b.AddOffset(fslot(1), platform);
        b.AddOffset(fslot(2), route);
        b.AddElement<int32_t>(fslot(3), 18 + (i * 13) % 240, 0);
        b.AddElement<int32_t>(fslot(4), 1000 + i * 431, 0);
        b.AddElement<double>(fslot(5), 0.72 + (i % 25) * 0.01, 0.0);
        b.AddElement<double>(fslot(6), 0.0007 * (i + 1), 0.0);
        b.AddElement<int32_t>(fslot(7), 12 + i * 7, 0);
        b.AddOffset(fslot(8), owner);
        models.push_back(endTable(b, start));
    }
    std::vector<FbTableOffset> evals;
    for (int i = 0; i < 48; ++i) {
        auto suite = b.CreateString(token("eval", i));
        auto start = b.StartTable();
        b.AddOffset(fslot(0), suite);
        b.AddElement<int32_t>(fslot(1), 80 + i % 17, 0);
        b.AddElement<int32_t>(fslot(2), i % 5, 0);
        b.AddElement<double>(fslot(3), 0.8 + (i % 13) * 0.01, 0.0);
        evals.push_back(endTable(b, start));
    }
    auto model_vec = b.CreateVector(models);
    auto eval_vec = b.CreateVector(evals);
    auto start = b.StartTable();
    b.AddOffset(fslot(0), model_vec);
    b.AddOffset(fslot(1), eval_vec);
    return finishFlat(b, endTable(b, start));
}

void scanFlexAiRegistry(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    auto models = values[0].AsVector();
    for (size_t i = 0; i < models.size(); i += 8) {
        auto model = models[i].AsMap().Values();
        sinkFlexString(model[0].AsString());
        sinkFlexString(model[1].AsString());
        sink(model[3].AsInt64());
        sink(model[6].AsDouble());
    }
}

void scanFlatAiRegistry(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    auto models = root->GetPointer<const FbTableVec*>(fslot(0));
    for (flatbuffers::uoffset_t i = 0; i < models->size(); i += 8) {
        auto model = models->Get(i);
        sinkFbString(model->GetPointer<const FbString*>(fslot(0)));
        sinkFbString(model->GetPointer<const FbString*>(fslot(1)));
        sink(model->GetField<int32_t>(fslot(3), 0));
        sink(model->GetField<double>(fslot(6), 0.0));
    }
}

void fullFlexAiRegistry(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    auto models = values[0].AsVector();
    for (size_t i = 0; i < models.size(); ++i) {
        auto model = models[i].AsMap().Values();
        sinkFlexString(model[0].AsString());
        sinkFlexString(model[1].AsString());
        sinkFlexString(model[2].AsString());
        sink(model[3].AsInt64());
        sink(model[4].AsInt64());
        sink(model[5].AsDouble());
        sink(model[6].AsDouble());
        sink(model[7].AsInt64());
        sinkFlexString(model[8].AsString());
    }
    auto evals = values[1].AsVector();
    for (size_t i = 0; i < evals.size(); ++i) {
        auto eval = evals[i].AsMap().Values();
        sinkFlexString(eval[0].AsString());
        sink(eval[1].AsInt64());
        sink(eval[2].AsInt64());
        sink(eval[3].AsDouble());
    }
}

void fullFlatAiRegistry(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    auto models = root->GetPointer<const FbTableVec*>(fslot(0));
    for (flatbuffers::uoffset_t i = 0; i < models->size(); ++i) {
        auto model = models->Get(i);
        sinkFbString(model->GetPointer<const FbString*>(fslot(0)));
        sinkFbString(model->GetPointer<const FbString*>(fslot(1)));
        sinkFbString(model->GetPointer<const FbString*>(fslot(2)));
        sink(model->GetField<int32_t>(fslot(3), 0));
        sink(model->GetField<int32_t>(fslot(4), 0));
        sink(model->GetField<double>(fslot(5), 0.0));
        sink(model->GetField<double>(fslot(6), 0.0));
        sink(model->GetField<int32_t>(fslot(7), 0));
        sinkFbString(model->GetPointer<const FbString*>(fslot(8)));
    }
    auto evals = root->GetPointer<const FbTableVec*>(fslot(1));
    for (flatbuffers::uoffset_t i = 0; i < evals->size(); ++i) {
        auto eval = evals->Get(i);
        sinkFbString(eval->GetPointer<const FbString*>(fslot(0)));
        sink(eval->GetField<int32_t>(fslot(1), 0));
        sink(eval->GetField<int32_t>(fslot(2), 0));
        sink(eval->GetField<double>(fslot(3), 0.0));
    }
}

// 12. DevTools/network/log trace: request rows and downstream invocation detail.
Bytes flexNetworkTrace() {
    flexbuffers::Builder b(65536, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.Vector("f00_requests", [&] {
            for (int i = 0; i < 220; ++i) {
                b.Map([&] {
                    b.String("f00_method", (i % 4 == 0) ? "GET" : (i % 4 == 1) ? "POST" : (i % 4 == 2) ? "PUT" : "DELETE");
                    b.String("f01_path", token("/api/graph/node", i).c_str());
                    b.Int("f02_status", (i % 37 == 0) ? 500 : (i % 11 == 0) ? 404 : 200);
                    b.Int("f03_duration_ms", 12 + (i * 19) % 600);
                    b.Int("f04_bytes", 512 + i * 113);
                    b.String("f05_worker", (i % 3 == 0) ? "auth" : (i % 3 == 1) ? "social" : "messaging");
                    b.Bool("f06_cache", (i % 5) == 0);
                });
            }
        });
        b.Int("f01_errors", 6);
        b.Double("f02_p95", 142.0);
    });
    b.Finish();
    return b.GetBuffer();
}

Bytes flatNetworkTrace() {
    flatbuffers::FlatBufferBuilder b(65536);
    std::vector<FbTableOffset> requests;
    for (int i = 0; i < 220; ++i) {
        auto method = b.CreateString((i % 4 == 0) ? "GET" : (i % 4 == 1) ? "POST" : (i % 4 == 2) ? "PUT" : "DELETE");
        auto path = b.CreateString(token("/api/graph/node", i));
        auto worker = b.CreateString((i % 3 == 0) ? "auth" : (i % 3 == 1) ? "social" : "messaging");
        auto start = b.StartTable();
        b.AddOffset(fslot(0), method);
        b.AddOffset(fslot(1), path);
        b.AddElement<int32_t>(fslot(2), (i % 37 == 0) ? 500 : (i % 11 == 0) ? 404 : 200, 0);
        b.AddElement<int32_t>(fslot(3), 12 + (i * 19) % 600, 0);
        b.AddElement<int32_t>(fslot(4), 512 + i * 113, 0);
        b.AddOffset(fslot(5), worker);
        b.AddElement<uint8_t>(fslot(6), (i % 5) == 0 ? 1 : 0, 0);
        requests.push_back(endTable(b, start));
    }
    auto request_vec = b.CreateVector(requests);
    auto start = b.StartTable();
    b.AddOffset(fslot(0), request_vec);
    b.AddElement<int32_t>(fslot(1), 6, 0);
    b.AddElement<double>(fslot(2), 142.0, 0.0);
    return finishFlat(b, endTable(b, start));
}

void scanFlexNetworkTrace(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    auto requests = values[0].AsVector();
    for (size_t i = 0; i < requests.size(); i += 31) {
        auto request = requests[i].AsMap().Values();
        sinkFlexString(request[0].AsString());
        sink(request[2].AsInt64());
        sink(request[3].AsInt64());
        sink(request[6].AsBool());
    }
}

void scanFlatNetworkTrace(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    auto requests = root->GetPointer<const FbTableVec*>(fslot(0));
    for (flatbuffers::uoffset_t i = 0; i < requests->size(); i += 31) {
        auto request = requests->Get(i);
        sinkFbString(request->GetPointer<const FbString*>(fslot(0)));
        sink(request->GetField<int32_t>(fslot(2), 0));
        sink(request->GetField<int32_t>(fslot(3), 0));
        sink(request->GetField<uint8_t>(fslot(6), 0) != 0);
    }
}

void fullFlexNetworkTrace(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    auto requests = values[0].AsVector();
    for (size_t i = 0; i < requests.size(); ++i) {
        auto request = requests[i].AsMap().Values();
        sinkFlexString(request[0].AsString());
        sinkFlexString(request[1].AsString());
        sink(request[2].AsInt64());
        sink(request[3].AsInt64());
        sink(request[4].AsInt64());
        sinkFlexString(request[5].AsString());
        sink(request[6].AsBool());
    }
    sink(values[1].AsInt64());
    sink(values[2].AsDouble());
}

void fullFlatNetworkTrace(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    auto requests = root->GetPointer<const FbTableVec*>(fslot(0));
    for (flatbuffers::uoffset_t i = 0; i < requests->size(); ++i) {
        auto request = requests->Get(i);
        sinkFbString(request->GetPointer<const FbString*>(fslot(0)));
        sinkFbString(request->GetPointer<const FbString*>(fslot(1)));
        sink(request->GetField<int32_t>(fslot(2), 0));
        sink(request->GetField<int32_t>(fslot(3), 0));
        sink(request->GetField<int32_t>(fslot(4), 0));
        sinkFbString(request->GetPointer<const FbString*>(fslot(5)));
        sink(request->GetField<uint8_t>(fslot(6), 0) != 0);
    }
    sink(root->GetField<int32_t>(fslot(1), 0));
    sink(root->GetField<double>(fslot(2), 0.0));
}

// 13. Logs/string corpus: the adversarial string-heavy pane case.
Bytes flexLogCorpus() {
    flexbuffers::Builder b(131072, flexbuffers::BUILDER_FLAG_SHARE_KEYS_AND_STRINGS);
    b.Map([&] {
        b.Vector("f00_lines", [&] {
            for (int i = 0; i < 420; ++i) {
                b.Map([&] {
                    b.Int("f00_ts", 1716650000 + i);
                    b.Int("f01_level", i % 5);
                    b.String("f02_source", (i % 4 == 0) ? "worker/auth" : (i % 4 == 1) ? "worker/social" : (i % 4 == 2) ? "desktop/runtime" : "engine/render");
                    std::string message = "Reaktor event " + std::to_string(i) + " graph=node:" + std::to_string(i % 97) + " status=" + ((i % 17 == 0) ? "warning" : "ok") + " path=/onboarding trace=tr_" + std::to_string(i * 13);
                    b.String("f03_message", message.c_str());
                    b.String("f04_trace", token("trace", i % 64).c_str());
                });
            }
        });
        b.String("f01_query", "level:warn OR service:auth");
    });
    b.Finish();
    return b.GetBuffer();
}

Bytes flatLogCorpus() {
    flatbuffers::FlatBufferBuilder b(131072);
    std::vector<FbTableOffset> lines;
    for (int i = 0; i < 420; ++i) {
        auto source = b.CreateString((i % 4 == 0) ? "worker/auth" : (i % 4 == 1) ? "worker/social" : (i % 4 == 2) ? "desktop/runtime" : "engine/render");
        std::string message = "Reaktor event " + std::to_string(i) + " graph=node:" + std::to_string(i % 97) + " status=" + ((i % 17 == 0) ? "warning" : "ok") + " path=/onboarding trace=tr_" + std::to_string(i * 13);
        auto message_offset = b.CreateString(message);
        auto trace = b.CreateString(token("trace", i % 64));
        auto start = b.StartTable();
        b.AddElement<int64_t>(fslot(0), 1716650000 + i, 0);
        b.AddElement<int32_t>(fslot(1), i % 5, 0);
        b.AddOffset(fslot(2), source);
        b.AddOffset(fslot(3), message_offset);
        b.AddOffset(fslot(4), trace);
        lines.push_back(endTable(b, start));
    }
    auto line_vec = b.CreateVector(lines);
    auto query = b.CreateString("level:warn OR service:auth");
    auto start = b.StartTable();
    b.AddOffset(fslot(0), line_vec);
    b.AddOffset(fslot(1), query);
    return finishFlat(b, endTable(b, start));
}

void scanFlexLogCorpus(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    auto lines = values[0].AsVector();
    for (size_t i = 0; i < lines.size(); i += 53) {
        auto line = lines[i].AsMap().Values();
        sink(line[0].AsInt64());
        sink(line[1].AsInt64());
        sinkFlexString(line[2].AsString());
        sinkFlexString(line[4].AsString());
    }
}

void scanFlatLogCorpus(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    auto lines = root->GetPointer<const FbTableVec*>(fslot(0));
    for (flatbuffers::uoffset_t i = 0; i < lines->size(); i += 53) {
        auto line = lines->Get(i);
        sink(line->GetField<int64_t>(fslot(0), 0));
        sink(line->GetField<int32_t>(fslot(1), 0));
        sinkFbString(line->GetPointer<const FbString*>(fslot(2)));
        sinkFbString(line->GetPointer<const FbString*>(fslot(4)));
    }
}

void fullFlexLogCorpus(const Bytes& bytes) {
    Checksum checksum;
    auto values = flexbuffers::GetRoot(bytes).AsMap().Values();
    auto lines = values[0].AsVector();
    for (size_t i = 0; i < lines.size(); ++i) {
        auto line = lines[i].AsMap().Values();
        sink(line[0].AsInt64());
        sink(line[1].AsInt64());
        sinkFlexString(line[2].AsString());
        sinkFlexString(line[3].AsString());
        sinkFlexString(line[4].AsString());
    }
    sinkFlexString(values[1].AsString());
}

void fullFlatLogCorpus(const Bytes& bytes) {
    Checksum checksum;
    auto root = rootTable(bytes);
    auto lines = root->GetPointer<const FbTableVec*>(fslot(0));
    for (flatbuffers::uoffset_t i = 0; i < lines->size(); ++i) {
        auto line = lines->Get(i);
        sink(line->GetField<int64_t>(fslot(0), 0));
        sink(line->GetField<int32_t>(fslot(1), 0));
        sinkFbString(line->GetPointer<const FbString*>(fslot(2)));
        sinkFbString(line->GetPointer<const FbString*>(fslot(3)));
        sinkFbString(line->GetPointer<const FbString*>(fslot(4)));
    }
    sinkFbString(root->GetPointer<const FbString*>(fslot(1)));
}

std::vector<CaseDef> cases() {
    return {
        {
            "FlatPrimitives",
            "status strips, selected node identity, compact counters",
            flexFlatPrimitives,
            flatFlatPrimitives,
            scanFlexFlatPrimitives,
            scanFlatFlatPrimitives,
            fullFlexFlatPrimitives,
            fullFlatFlatPrimitives,
            768,
        },
        {
            "UserProfile",
            "auth/profile/session metadata with strings, tags, settings",
            flexUserProfile,
            flatUserProfile,
            scanFlexUserProfile,
            scanFlatUserProfile,
            fullFlexUserProfile,
            fullFlatUserProfile,
            2048,
        },
        {
            "DatabaseRows",
            "database result grids and row inspector panes",
            flexDatabaseRows,
            flatDatabaseRows,
            scanFlexDatabaseRows,
            scanFlatDatabaseRows,
            fullFlexDatabaseRows,
            fullFlatDatabaseRows,
            3072,
        },
        {
            "GraphSnapshot",
            "graph nodes, edges, warnings and inspector fields",
            flexGraphSnapshot,
            flatGraphSnapshot,
            scanFlexGraphSnapshot,
            scanFlatGraphSnapshot,
            fullFlexGraphSnapshot,
            fullFlatGraphSnapshot,
            4096,
        },
        {
            "CommandQueue",
            "command queue, code agent, codediff status rows",
            flexCommandQueue,
            flatCommandQueue,
            scanFlexCommandQueue,
            scanFlatCommandQueue,
            fullFlexCommandQueue,
            fullFlatCommandQueue,
            3072,
        },
        {
            "AgentTrace",
            "AI/agent conversation events, token/cost traces, MCP context",
            flexAgentTrace,
            flatAgentTrace,
            scanFlexAgentTrace,
            scanFlatAgentTrace,
            fullFlexAgentTrace,
            fullFlatAgentTrace,
            4096,
        },
        {
            "TimeSeries",
            "telemetry traces, latency samples, insight charts",
            flexTimeSeries,
            flatTimeSeries,
            scanFlexTimeSeries,
            scanFlatTimeSeries,
            fullFlexTimeSeries,
            fullFlatTimeSeries,
            1536,
        },
        {
            "DeployInsights",
            "deploy partitions, checks, active user and cost counters",
            flexDeployInsights,
            flatDeployInsights,
            scanFlexDeployInsights,
            scanFlatDeployInsights,
            fullFlexDeployInsights,
            fullFlatDeployInsights,
            3072,
        },
        {
            "AuthPolicy",
            "auth roles, providers, sessions, permission matrix",
            flexAuthPermissionMatrix,
            flatAuthPermissionMatrix,
            scanFlexAuthPermissionMatrix,
            scanFlatAuthPermissionMatrix,
            fullFlexAuthPermissionMatrix,
            fullFlatAuthPermissionMatrix,
            4096,
        },
        {
            "TestingMatrix",
            "test suites, replay/proxy/device matrix rows",
            flexTestingMatrix,
            flatTestingMatrix,
            scanFlexTestingMatrix,
            scanFlatTestingMatrix,
            fullFlexTestingMatrix,
            fullFlatTestingMatrix,
            3072,
        },
        {
            "AiRegistry",
            "AI models, gateway routes, evals and cost metadata",
            flexAiRegistry,
            flatAiRegistry,
            scanFlexAiRegistry,
            scanFlatAiRegistry,
            fullFlexAiRegistry,
            fullFlatAiRegistry,
            4096,
        },
        {
            "NetworkTrace",
            "DevTools network rows, worker detail and cache status",
            flexNetworkTrace,
            flatNetworkTrace,
            scanFlexNetworkTrace,
            scanFlatNetworkTrace,
            fullFlexNetworkTrace,
            fullFlatNetworkTrace,
            3072,
        },
        {
            "LogCorpus",
            "logs pane and string-heavy diagnostic rows",
            flexLogCorpus,
            flatLogCorpus,
            scanFlexLogCorpus,
            scanFlatLogCorpus,
            fullFlexLogCorpus,
            fullFlatLogCorpus,
            2048,
        },
    };
}

void parseArgs(int argc, char** argv) {
    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--quick") {
            warmup = 100;
            iterations = 1000;
            runs = 5;
        } else if (arg == "--verify" || arg == "--verify-only") {
            verify_only = true;
        } else if (arg == "--iters" && i + 1 < argc) {
            iterations = std::atoi(argv[++i]);
        } else if (arg == "--runs" && i + 1 < argc) {
            runs = std::atoi(argv[++i]);
        } else if (arg == "--warmup" && i + 1 < argc) {
            warmup = std::atoi(argv[++i]);
        } else {
            std::fprintf(stderr, "unknown argument: %s\n", arg.c_str());
            std::exit(2);
        }
    }
    if (warmup < 0 || iterations <= 0 || runs <= 0) {
        std::fprintf(stderr, "warmup must be non-negative; iters and runs must be positive\n");
        std::exit(2);
    }
}

uint64_t checksumAfter(
    const std::function<void(const Bytes&)>& scan,
    const Bytes& bytes
) {
    scan(bytes);
    return sink_value;
}

bool verifyCases(const std::vector<CaseDef>& all) {
    bool all_ok = true;
    for (const auto& c : all) {
        const Bytes flex_bytes = c.flex_encode();
        const Bytes flat_bytes = c.flat_encode();
        const uint64_t flex_partial = checksumAfter(c.flex_partial, flex_bytes);
        const uint64_t flat_partial = checksumAfter(c.flat_partial, flat_bytes);
        const uint64_t flex_full = checksumAfter(c.flex_full, flex_bytes);
        const uint64_t flat_full = checksumAfter(c.flat_full, flat_bytes);
        const bool partial_ok = flex_partial == flat_partial;
        const bool full_ok = flex_full == flat_full;
        const bool ok = !flex_bytes.empty() && !flat_bytes.empty() && partial_ok && full_ok;
        all_ok = all_ok && ok;
        std::printf(
            "CPP_VERIFY|cpp.flat_vs_flex.%s|status=%s|flex_bytes=%zu|flat_bytes=%zu|flat_plus_id_bytes=%zu|flat_plus_schema_bytes=%zu|flex_partial_checksum=%llu|flat_partial_checksum=%llu|flex_full_checksum=%llu|flat_full_checksum=%llu\n",
            c.name,
            ok ? "PASS" : "FAIL",
            flex_bytes.size(),
            flat_bytes.size(),
            flat_bytes.size() + 8,
            flat_bytes.size() + c.schema_bytes_estimate,
            static_cast<unsigned long long>(flex_partial),
            static_cast<unsigned long long>(flat_partial),
            static_cast<unsigned long long>(flex_full),
            static_cast<unsigned long long>(flat_full)
        );
    }
    std::printf(
        "CPP_VERIFY_SUMMARY|cpp.flat_vs_flex|status=%s|cases=%zu\n",
        all_ok ? "PASS" : "FAIL",
        all.size()
    );
    return all_ok;
}

void printMetric(
    const CaseDef& c,
    const char* format,
    const char* phase,
    const BenchStats& stats,
    size_t bytes,
    uint64_t semantic_checksum
) {
    std::printf(
        "CPP_METRIC|cpp.flat_vs_flex.%s.%s.%s|median_us=%.9f|min_us=%.9f|max_us=%.9f|mean_us=%.9f|sd_us=%.9f|bytes=%zu|semantic_checksum=%llu|warmup=%d|iters=%d|runs=%d\n",
        c.name,
        format,
        phase,
        stats.median,
        stats.minimum,
        stats.maximum,
        stats.mean,
        stats.standard_deviation,
        bytes,
        static_cast<unsigned long long>(semantic_checksum),
        warmup,
        iterations,
        runs
    );
}

void printSchemaNote() {
    std::printf("\nSchema note:\n");
    std::printf("  This file uses fixed FlatBuffers field slots directly. Generated code would\n");
    std::printf("  call the same Table::GetField/GetPointer paths. If a .fbs schema is sent\n");
    std::printf("  with every payload, add the schema/reflection bytes to the FlatBuffer size\n");
    std::printf("  column. Runtime scans below do not include schema parsing because normal\n");
    std::printf("  FlatBuffers access does not need to parse a schema per message.\n\n");
}

} // namespace

int main(int argc, char** argv) {
    parseArgs(argc, argv);

    auto all = cases();
    if (!verifyCases(all)) return 1;
    if (verify_only) return 0;

    std::printf("Reaktor FlatBuffers vs FlexBuffers C++ benchmark\n");
    std::printf("warmup=%d iterations=%d runs=%d\n", warmup, iterations, runs);
    printSchemaNote();

    std::printf("%-16s %9s %9s %6s %9s %9s %6s %9s %9s %6s %8s %8s %9s %11s\n",
                "Case", "FlexEnc", "FlatEnc", "EncX", "FlexPart", "FlatPart", "PartX",
                "FlexFull", "FlatFull", "FullX", "FlexB", "FlatB", "Flat+Id", "Flat+Schema");
    std::printf("%s\n", std::string(142, '-').c_str());

    int flat_faster_encode = 0;
    int flat_faster_partial = 0;
    int flat_faster_full = 0;
    int flat_smaller = 0;
    int flat_id_smaller = 0;
    int flat_schema_smaller = 0;
    double enc_log = 0.0;
    double partial_log = 0.0;
    double full_log = 0.0;

    for (const auto& c : all) {
        Bytes flex_bytes = c.flex_encode();
        Bytes flat_bytes = c.flat_encode();

        const uint64_t flex_partial_checksum = checksumAfter(c.flex_partial, flex_bytes);
        const uint64_t flat_partial_checksum = checksumAfter(c.flat_partial, flat_bytes);
        const uint64_t flex_full_checksum = checksumAfter(c.flex_full, flex_bytes);
        const uint64_t flat_full_checksum = checksumAfter(c.flat_full, flat_bytes);

        BenchStats flex_enc = bench([&] {
            auto bytes = c.flex_encode();
            sink_value = static_cast<uint64_t>(bytes.size());
        });
        BenchStats flat_enc = bench([&] {
            auto bytes = c.flat_encode();
            sink_value = static_cast<uint64_t>(bytes.size());
        });
        BenchStats flex_partial = bench([&] { c.flex_partial(flex_bytes); });
        BenchStats flat_partial = bench([&] { c.flat_partial(flat_bytes); });
        BenchStats flex_full = bench([&] { c.flex_full(flex_bytes); });
        BenchStats flat_full = bench([&] { c.flat_full(flat_bytes); });

        double enc_ratio = flex_enc.median / flat_enc.median;
        double partial_ratio = flex_partial.median / flat_partial.median;
        double full_ratio = flex_full.median / flat_full.median;
        size_t flat_with_id = flat_bytes.size() + 8;
        size_t flat_with_schema = flat_bytes.size() + c.schema_bytes_estimate;

        if (flat_enc.median < flex_enc.median) ++flat_faster_encode;
        if (flat_partial.median < flex_partial.median) ++flat_faster_partial;
        if (flat_full.median < flex_full.median) ++flat_faster_full;
        if (flat_bytes.size() < flex_bytes.size()) ++flat_smaller;
        if (flat_with_id < flex_bytes.size()) ++flat_id_smaller;
        if (flat_with_schema < flex_bytes.size()) ++flat_schema_smaller;
        enc_log += std::log(enc_ratio);
        partial_log += std::log(partial_ratio);
        full_log += std::log(full_ratio);

        std::printf("%-16s %7.3fus %7.3fus %5.1fx %7.3fus %7.3fus %5.1fx %7.3fus %7.3fus %5.1fx %8zu %8zu %9zu %11zu\n",
                    c.name,
                    flex_enc.median,
                    flat_enc.median,
                    enc_ratio,
                    flex_partial.median,
                    flat_partial.median,
                    partial_ratio,
                    flex_full.median,
                    flat_full.median,
                    full_ratio,
                    flex_bytes.size(),
                    flat_bytes.size(),
                    flat_with_id,
                    flat_with_schema);

        std::printf(
            "CPP_SIZE|cpp.flat_vs_flex.%s|flex_bytes=%zu|flat_bytes=%zu|flat_plus_id_bytes=%zu|flat_plus_schema_bytes=%zu|semantic_checksum=%llu\n",
            c.name,
            flex_bytes.size(),
            flat_bytes.size(),
            flat_with_id,
            flat_with_schema,
            static_cast<unsigned long long>(flex_full_checksum)
        );
        printMetric(c, "flex", "encode", flex_enc, flex_bytes.size(), flex_full_checksum);
        printMetric(c, "flat", "encode", flat_enc, flat_bytes.size(), flat_full_checksum);
        printMetric(c, "flex", "partial", flex_partial, flex_bytes.size(), flex_partial_checksum);
        printMetric(c, "flat", "partial", flat_partial, flat_bytes.size(), flat_partial_checksum);
        printMetric(c, "flex", "full", flex_full, flex_bytes.size(), flex_full_checksum);
        printMetric(c, "flat", "full", flat_full, flat_bytes.size(), flat_full_checksum);
    }

    std::printf("%s\n", std::string(142, '-').c_str());
    std::printf("FlatBuffers faster encode: %d/%zu\n", flat_faster_encode, all.size());
    std::printf("FlatBuffers faster partial: %d/%zu\n", flat_faster_partial, all.size());
    std::printf("FlatBuffers faster full:    %d/%zu\n", flat_faster_full, all.size());
    std::printf("FlatBuffers smaller raw:    %d/%zu\n", flat_smaller, all.size());
    std::printf("FlatBuffers smaller +8B id: %d/%zu\n", flat_id_smaller, all.size());
    std::printf("FlatBuffers smaller +schema:%d/%zu\n", flat_schema_smaller, all.size());
    std::printf("Geomean speedup encode/partial/full: %.2fx / %.2fx / %.2fx\n",
                std::exp(enc_log / all.size()),
                std::exp(partial_log / all.size()),
                std::exp(full_log / all.size()));
    std::printf("sink=%llu\n", static_cast<unsigned long long>(sink_value));
    return 0;
}
