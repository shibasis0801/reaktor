#include <gtest/gtest.h>
#include <filesystem>
#include <fstream>
#include <set>
#include "../../../cpp/external/mlspp/cmd/interop/src/json_details.h"

using namespace mls_vectors;

namespace {
template<typename Vector>
void verify_vectors(const char* name, std::set<unsigned> expected, size_t minimum)
{
    const auto root = std::filesystem::path(__FILE__).parent_path().parent_path().parent_path().parent_path();
    std::ifstream input(root / "build/interop-vectors" / name, std::ios::binary);
    ASSERT_TRUE(input.good()) << "Missing pinned reference file: " << name;
    json data;
    try {
        input >> data;
    } catch (...) {
        FAIL() << "Cannot parse reference file: " << name;
    }
    ASSERT_TRUE(data.is_array());
    std::set<unsigned> seen;
    size_t checked = 0;
    for (size_t index = 0; index < data.size(); ++index) {
        const auto& item = data[index];
        try {
            if (item.contains("cipher_suite")) {
                const auto suite = item.at("cipher_suite").get<unsigned>();
                if (suite < 1 || suite > 3) continue;
                seen.insert(suite);
            }
            auto vector = item.get<Vector>();
            const auto failure = vector.verify();
            ASSERT_FALSE(failure.has_value()) << name << " vector " << index;
            ++checked;
        } catch (...) {
            FAIL() << "Reference verification threw: " << name << " vector " << index;
        }
    }
    EXPECT_GE(checked, minimum);
    EXPECT_TRUE(seen == expected);
    testing::Test::RecordProperty("reference_vectors", std::to_string(checked));
}
}

TEST(MlsInteropTest, TreeMath)
{
    verify_vectors<TreeMathTestVector>("tree-math.json", {}, 10);
}
TEST(MlsInteropTest, CryptoBasics)
{
    verify_vectors<CryptoBasicsTestVector>("crypto-basics.json", {1, 2, 3}, 3);
}
TEST(MlsInteropTest, SecretTree)
{
    verify_vectors<SecretTreeTestVector>("secret-tree.json", {1, 2, 3}, 9);
}
TEST(MlsInteropTest, MessageProtection)
{
    verify_vectors<MessageProtectionTestVector>("message-protection.json", {1, 2, 3}, 3);
}
TEST(MlsInteropTest, KeySchedule)
{
    verify_vectors<KeyScheduleTestVector>("key-schedule.json", {1, 2, 3}, 3);
}
TEST(MlsInteropTest, PskSecret)
{
    verify_vectors<PSKSecretTestVector>("psk_secret.json", {1, 2, 3}, 33);
}
TEST(MlsInteropTest, TreeValidation)
{
    verify_vectors<TreeHashTestVector>("tree-validation.json", {1, 2, 3}, 42);
}
TEST(MlsInteropTest, TranscriptHashes)
{
    verify_vectors<TranscriptTestVector>("transcript-hashes.json", {1, 2, 3}, 3);
}
TEST(MlsInteropTest, Welcome)
{
    verify_vectors<WelcomeTestVector>("welcome.json", {1, 2, 3}, 3);
}
TEST(MlsInteropTest, TreeOperations)
{
    verify_vectors<TreeOperationsTestVector>("tree-operations.json", {1}, 5);
}
TEST(MlsInteropTest, TreeKem)
{
    verify_vectors<TreeKEMTestVector>("treekem.json", {1, 2, 3}, 33);
}
TEST(MlsInteropTest, Messages)
{
    verify_vectors<MessagesTestVector>("messages.json", {}, 300);
}
TEST(MlsInteropTest, PassiveWelcome)
{
    verify_vectors<PassiveClientTestVector>("passive-client-welcome.json", {1, 2, 3}, 24);
}
TEST(MlsInteropTest, PassiveCommits)
{
    verify_vectors<PassiveClientTestVector>("passive-client-handling-commit.json", {1, 2, 3}, 39);
}
TEST(MlsInteropTest, PassiveRandom)
{
    verify_vectors<PassiveClientTestVector>("passive-client-random.json", {1}, 1);
}

