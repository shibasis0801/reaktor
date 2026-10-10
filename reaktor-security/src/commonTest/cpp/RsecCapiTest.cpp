#include <gtest/gtest.h>

#include <rsec/rsec.h>

#include <cstring>
#include <string>

namespace {

rsec_buffer_t
empty_buffer()
{
  return rsec_buffer_t{ nullptr, 0 };
}

rsec_engine_t*
new_engine(const std::string& credential)
{
  rsec_engine_t* engine = nullptr;
  auto config = rsec_engine_config_t{
    reinterpret_cast<const uint8_t*>(credential.data()),
    credential.size(),
    0x0003,
  };
  EXPECT_EQ(rsec_engine_create(&config, &engine), RSEC_OK);
  return engine;
}

} // namespace

TEST(RsecCapiTest, CreateDestroyAndInvalidInput)
{
  rsec_engine_t* engine = nullptr;
  EXPECT_EQ(rsec_engine_create(nullptr, &engine), RSEC_ERR_INVALID_ARGUMENT);
  EXPECT_EQ(engine, nullptr);

  engine = new_engine("tenant-a:app-a:alice:device-1");
  ASSERT_NE(engine, nullptr);
  rsec_engine_destroy(engine);
}

TEST(RsecCapiTest, DirectConversationRoundTrip)
{
  auto* alice = new_engine("tenant-a:app-a:alice:device-1");
  auto* bob = new_engine("tenant-a:app-a:bob:device-1");
  ASSERT_NE(alice, nullptr);
  ASSERT_NE(bob, nullptr);

  auto bob_key_package = empty_buffer();
  ASSERT_EQ(rsec_generate_key_package(bob, &bob_key_package), RSEC_OK);
  ASSERT_NE(bob_key_package.data, nullptr);
  ASSERT_GT(bob_key_package.len, 0U);

  const auto conversation = std::string("conversation-capi");
  auto welcome = empty_buffer();
  auto commit = empty_buffer();
  ASSERT_EQ(rsec_create_group(alice,
                              reinterpret_cast<const uint8_t*>(conversation.data()),
                              conversation.size(),
                              &bob_key_package,
                              1,
                              &welcome,
                              &commit),
            RSEC_OK);
  ASSERT_GT(welcome.len, 0U);
  ASSERT_GT(commit.len, 0U);

  ASSERT_EQ(rsec_join_group(bob,
                            reinterpret_cast<const uint8_t*>(conversation.data()),
                            conversation.size(),
                            welcome.data,
                            welcome.len),
            RSEC_OK);

  const auto aad = std::string("aad");
  const auto plaintext = std::string("hello through c");
  auto ciphertext = empty_buffer();
  ASSERT_EQ(rsec_encrypt_message(alice,
                                 reinterpret_cast<const uint8_t*>(conversation.data()),
                                 conversation.size(),
                                 reinterpret_cast<const uint8_t*>(aad.data()),
                                 aad.size(),
                                 reinterpret_cast<const uint8_t*>(plaintext.data()),
                                 plaintext.size(),
                                 &ciphertext),
            RSEC_OK);

  auto decrypted_aad = empty_buffer();
  auto decrypted_plaintext = empty_buffer();
  ASSERT_EQ(rsec_decrypt_message(bob,
                                 reinterpret_cast<const uint8_t*>(conversation.data()),
                                 conversation.size(),
                                 ciphertext.data,
                                 ciphertext.len,
                                 &decrypted_aad,
                                 &decrypted_plaintext),
            RSEC_OK);

  ASSERT_EQ(decrypted_aad.len, aad.size());
  EXPECT_EQ(std::memcmp(decrypted_aad.data, aad.data(), aad.size()), 0);
  ASSERT_EQ(decrypted_plaintext.len, plaintext.size());
  EXPECT_EQ(
    std::memcmp(decrypted_plaintext.data, plaintext.data(), plaintext.size()),
    0);

  rsec_buffer_free(decrypted_plaintext);
  rsec_buffer_free(decrypted_aad);
  rsec_buffer_free(ciphertext);
  rsec_buffer_free(commit);
  rsec_buffer_free(welcome);
  rsec_buffer_free(bob_key_package);
  rsec_engine_destroy(bob);
  rsec_engine_destroy(alice);
}
