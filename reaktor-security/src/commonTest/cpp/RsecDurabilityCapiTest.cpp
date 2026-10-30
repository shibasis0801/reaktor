#include <gtest/gtest.h>
#include <rsec/rsec.h>
#include <memory>
#include <cstring>

namespace {
using Engine = std::unique_ptr<rsec_engine_t, decltype(&rsec_engine_destroy)>;
Engine engine(uint8_t credential)
{
  rsec_engine_t* value = nullptr;
  const rsec_engine_config_t config{&credential, 1, 3};
  if (rsec_engine_create(&config, &value) != RSEC_OK) throw std::runtime_error("Engine creation failed");
  return {value, rsec_engine_destroy};
}
constexpr uint8_t chat[] = {'c', 'h', 'a', 't'};
}

TEST(RsecDurabilityCapiTest, FailedCallsClearOutputsAndRejectInvalidLengths)
{
  EXPECT_EQ(rsec_abi_version(), 2U);
  auto* invalid = reinterpret_cast<rsec_engine_t*>(1);
  EXPECT_EQ(rsec_engine_create_verified(nullptr, 3, &invalid), RSEC_ERR_INVALID_ARGUMENT);
  EXPECT_EQ(invalid, nullptr);
  EXPECT_EQ(rsec_engine_restore(nullptr, 1, &invalid), RSEC_ERR_INVALID_ARGUMENT);
  EXPECT_EQ(invalid, nullptr);
  auto alice = engine(1);
  rsec_buffer_t out{reinterpret_cast<uint8_t*>(1), 1};
  EXPECT_EQ(rsec_generate_key_package(nullptr, &out), RSEC_ERR_INVALID_ARGUMENT);
  EXPECT_EQ(out.data, nullptr);
  EXPECT_EQ(out.len, 0U);
  EXPECT_EQ(rsec_storage_open(nullptr, 32, nullptr, 0, nullptr, 0, &out), RSEC_ERR_INVALID_ARGUMENT);
  EXPECT_EQ(out.data, nullptr);
  EXPECT_EQ(rsec_storage_seal(nullptr, 32, nullptr, 0, nullptr, size_t(1) << 40, &out), RSEC_ERR_INVALID_ARGUMENT);
  rsec_commit_t commit{};
  EXPECT_EQ(rsec_prepare_create(alice.get(), chat, sizeof(chat), nullptr, 1, &commit), RSEC_ERR_INVALID_ARGUMENT);
  EXPECT_EQ(commit.commit.data, nullptr);
  EXPECT_EQ(rsec_prepare_create(alice.get(), chat, sizeof(chat), nullptr, size_t(1) << 40, &commit), RSEC_ERR_INVALID_ARGUMENT);
  uint64_t epoch = 99;
  EXPECT_EQ(rsec_epoch(alice.get(), chat, sizeof(chat), &epoch), RSEC_ERR_UNKNOWN_GROUP);
  EXPECT_EQ(epoch, 0U);
  uint8_t advanced = 99;
  EXPECT_EQ(rsec_process_handshake(nullptr, chat, sizeof(chat), nullptr, 1, &advanced), RSEC_ERR_INVALID_ARGUMENT);
  EXPECT_EQ(advanced, 0U);
  EXPECT_EQ(rsec_roster_count(alice.get(), chat, sizeof(chat), nullptr), RSEC_ERR_INVALID_ARGUMENT);
  rsec_buffer_free({});
  rsec_commit_free({});
  rsec_sealed_content_free({});
  rsec_opened_content_free({});
  rsec_engine_destroy(nullptr);
  rsec_attachment_sealer_destroy(nullptr);
  rsec_attachment_opener_destroy(nullptr);
}

TEST(RsecDurabilityCapiTest, PendingStateAndOwnedContentSurviveTheAbiRestart)
{
  auto alice = engine(1);
  auto bob = engine(2);
  rsec_buffer_t package{};
  ASSERT_EQ(rsec_generate_key_package(bob.get(), &package), RSEC_OK);
  rsec_commit_t created{};
  ASSERT_EQ(rsec_prepare_create(alice.get(), chat, sizeof(chat), &package, 1, &created), RSEC_OK);
  rsec_buffer_free(package);
  rsec_buffer_t snapshot{};
  ASSERT_EQ(rsec_engine_snapshot(alice.get(), &snapshot), RSEC_OK);
  alice.reset();
  rsec_engine_t* restored = nullptr;
  ASSERT_EQ(rsec_engine_restore(snapshot.data, snapshot.len, &restored), RSEC_OK);
  alice.reset(restored);
  rsec_buffer_free(snapshot);
  rsec_commit_t pending{};
  ASSERT_EQ(rsec_pending_commit(alice.get(), chat, sizeof(chat), &pending), RSEC_OK);
  EXPECT_TRUE(pending.commit.len == created.commit.len &&
    std::memcmp(pending.commit.data, created.commit.data, pending.commit.len) == 0);
  uint64_t epoch = 0;
  ASSERT_EQ(rsec_accept_pending(alice.get(), chat, sizeof(chat), pending.commit.data, pending.commit.len, &epoch), RSEC_OK);
  ASSERT_EQ(rsec_join_group(bob.get(), chat, sizeof(chat), created.welcome.data, created.welcome.len), RSEC_OK);
  rsec_commit_free(pending);
  rsec_commit_free(created);
  const uint8_t body[] = {1, 2, 3};
  rsec_sealed_content_t sealed{};
  ASSERT_EQ(rsec_seal_content(alice.get(), chat, sizeof(chat), nullptr, 0, body, sizeof(body), 0, 0, &sealed), RSEC_OK);
  rsec_opened_content_t opened{};
  ASSERT_EQ(rsec_open_content(bob.get(), chat, sizeof(chat), nullptr, 0, &sealed, 0, &opened), RSEC_OK);
  EXPECT_TRUE(opened.plaintext.len == sizeof(body) && std::memcmp(opened.plaintext.data, body, sizeof(body)) == 0);
  EXPECT_EQ(opened.authenticated, 1);
  EXPECT_EQ(opened.duplicate, 0);
  rsec_opened_content_free(opened);
  ASSERT_EQ(rsec_open_content(bob.get(), chat, sizeof(chat), nullptr, 0, &sealed, 0, &opened), RSEC_OK);
  EXPECT_EQ(opened.duplicate, 1);
  rsec_opened_content_free(opened);
  sealed.ciphertext.data[sealed.ciphertext.len - 1] ^= 1;
  EXPECT_NE(rsec_open_content(bob.get(), chat, sizeof(chat), nullptr, 0, &sealed, 0, &opened), RSEC_OK);
  EXPECT_EQ(opened.plaintext.data, nullptr);
  EXPECT_EQ(opened.franking_key.data, nullptr);
  EXPECT_EQ(opened.sender_credential.data, nullptr);
  rsec_sealed_content_free(sealed);
}

TEST(RsecDurabilityCapiTest, AttachmentAndStorageOwnershipCrossTheAbi)
{
  rsec_attachment_sealer_t* raw = nullptr;
  ASSERT_EQ(rsec_attachment_sealer_create(&raw), RSEC_OK);
  std::unique_ptr<rsec_attachment_sealer_t, decltype(&rsec_attachment_sealer_destroy)> sealer(raw, rsec_attachment_sealer_destroy);
  rsec_buffer_t key{}, chunk{}, digest{};
  ASSERT_EQ(rsec_attachment_key(sealer.get(), &key), RSEC_OK);
  EXPECT_NE(rsec_attachment_digest(sealer.get(), &digest), RSEC_OK);
  EXPECT_EQ(digest.data, nullptr);
  const uint8_t body[] = {3, 2, 1};
  ASSERT_EQ(rsec_attachment_seal(sealer.get(), body, sizeof(body), 1, &chunk), RSEC_OK);
  ASSERT_EQ(rsec_attachment_digest(sealer.get(), &digest), RSEC_OK);
  rsec_attachment_opener_t* opened_stream = nullptr;
  ASSERT_EQ(rsec_attachment_opener_create(key.data, key.len, digest.data, digest.len, &opened_stream), RSEC_OK);
  std::unique_ptr<rsec_attachment_opener_t, decltype(&rsec_attachment_opener_destroy)> opener(opened_stream, rsec_attachment_opener_destroy);
  EXPECT_NE(rsec_attachment_finish(opener.get()), RSEC_OK);
  rsec_buffer_t plaintext{};
  ASSERT_EQ(rsec_attachment_open(opener.get(), chunk.data, chunk.len, &plaintext), RSEC_OK);
  ASSERT_EQ(rsec_attachment_finish(opener.get()), RSEC_OK);
  EXPECT_TRUE(plaintext.len == sizeof(body) && std::memcmp(plaintext.data, body, sizeof(body)) == 0);
  rsec_buffer_free(plaintext);
  rsec_buffer_t sealed{};
  ASSERT_EQ(rsec_storage_seal(key.data, key.len, chat, sizeof(chat), body, sizeof(body), &sealed), RSEC_OK);
  ASSERT_EQ(rsec_storage_open(key.data, key.len, chat, sizeof(chat), sealed.data, sealed.len, &plaintext), RSEC_OK);
  EXPECT_TRUE(plaintext.len == sizeof(body) && std::memcmp(plaintext.data, body, sizeof(body)) == 0);
  rsec_buffer_free(plaintext);
  rsec_buffer_free(sealed);
  rsec_buffer_free(key);
  rsec_buffer_free(chunk);
  rsec_buffer_free(digest);
}
