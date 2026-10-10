#include <gtest/gtest.h>

#include <rsec/MlsEngine.h>

#include <string>

namespace {

rsec::Bytes
bytes(std::string value)
{
  return rsec::Bytes{ value.begin(), value.end() };
}

} // namespace

TEST(MlsEngineTest, DirectConversationEncryptsAndDecryptsBothWays)
{
  rsec::MlsEngine alice(bytes("tenant-a:app-a:alice:device-1"));
  rsec::MlsEngine bob(bytes("tenant-a:app-a:bob:device-1"));

  auto bob_packages = bob.generate_key_packages(1);
  auto created =
    alice.create_group("conversation-1", { bob_packages.front().key_package });

  ASSERT_FALSE(created.welcome.empty());
  ASSERT_FALSE(created.commit.empty());
  EXPECT_EQ(created.epoch, 1U);

  auto bob_epoch = bob.join_from_welcome("conversation-1", created.welcome);
  EXPECT_EQ(bob_epoch, created.epoch);

  auto aad = bytes("tenant-a|app-a|conversation-1|message-1");
  auto plaintext = bytes("hello bob");
  auto ciphertext =
    alice.encrypt_application_message("conversation-1", aad, plaintext);

  auto decrypted = bob.decrypt_application_message("conversation-1", ciphertext);
  EXPECT_EQ(decrypted.aad, aad);
  EXPECT_EQ(decrypted.plaintext, plaintext);

  auto reply = bytes("hello alice");
  auto reply_ciphertext =
    bob.encrypt_application_message("conversation-1", aad, reply);
  auto reply_decrypted =
    alice.decrypt_application_message("conversation-1", reply_ciphertext);
  EXPECT_EQ(reply_decrypted.aad, aad);
  EXPECT_EQ(reply_decrypted.plaintext, reply);
}

TEST(MlsEngineTest, AddMemberAdvancesEpochAndNewMemberCanDecrypt)
{
  rsec::MlsEngine alice(bytes("tenant-a:app-a:alice:device-1"));
  rsec::MlsEngine bob(bytes("tenant-a:app-a:bob:device-1"));
  rsec::MlsEngine carol(bytes("tenant-a:app-a:carol:device-1"));

  auto bob_package = bob.generate_key_packages(1).front().key_package;
  auto created = alice.create_group("conversation-2", { bob_package });
  bob.join_from_welcome("conversation-2", created.welcome);

  auto carol_package = carol.generate_key_packages(1).front().key_package;
  auto add_carol = alice.add_members("conversation-2", { carol_package });
  EXPECT_EQ(add_carol.epoch, 2U);

  uint64_t bob_before = bob.epoch("conversation-2");
  EXPECT_EQ(bob_before, 1U);
  EXPECT_TRUE(bob.process_handshake("conversation-2", add_carol.commit));
  EXPECT_EQ(bob.epoch("conversation-2"), 2U);

  auto carol_epoch =
    carol.join_from_welcome("conversation-2", add_carol.welcome);
  EXPECT_EQ(carol_epoch, 2U);

  auto aad = bytes("tenant-a|app-a|conversation-2|message-2");
  auto plaintext = bytes("welcome carol");
  auto ciphertext =
    bob.encrypt_application_message("conversation-2", aad, plaintext);

  auto alice_decrypted =
    alice.decrypt_application_message("conversation-2", ciphertext);
  auto carol_decrypted =
    carol.decrypt_application_message("conversation-2", ciphertext);

  EXPECT_EQ(alice_decrypted.aad, aad);
  EXPECT_EQ(alice_decrypted.plaintext, plaintext);
  EXPECT_EQ(carol_decrypted.aad, aad);
  EXPECT_EQ(carol_decrypted.plaintext, plaintext);
}

TEST(MlsEngineTest, UnknownConversationFails)
{
  rsec::MlsEngine alice(bytes("tenant-a:app-a:alice:device-1"));
  EXPECT_THROW(
    alice.encrypt_application_message("missing", {}, bytes("payload")),
    rsec::Error);
}
