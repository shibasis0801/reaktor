#include <gtest/gtest.h>
#include <rsec/Credential.h>
#include <rsec/rsec.h>
#include <openssl/bn.h>
#include <openssl/ec_key.h>
#include <openssl/ecdsa.h>
#include <openssl/nid.h>
#include <openssl/sha.h>
#include <memory>

namespace {
using namespace rsec;
constexpr auto suite = MlsCipherSuite::X25519_CHACHA20POLY1305_SHA256_Ed25519;

class Issuer
{
  std::unique_ptr<EC_KEY, decltype(&EC_KEY_free)> key{EC_KEY_new_by_curve_name(NID_X9_62_prime256v1), EC_KEY_free};
public:
  CredentialPolicy policy{"tenant", "app", {}};
  Issuer()
  {
    if (!key || EC_KEY_generate_key(key.get()) != 1) throw std::runtime_error("Issuer creation failed");
    Bytes public_key(65);
    if (EC_POINT_point2oct(EC_KEY_get0_group(key.get()), EC_KEY_get0_public_key(key.get()),
        POINT_CONVERSION_UNCOMPRESSED, public_key.data(), public_key.size(), nullptr) != public_key.size()) {
      throw std::runtime_error("Issuer public key encoding failed");
    }
    policy.auth_keys.emplace("auth-1", std::move(public_key));
  }

  Bytes sign(DeviceCredential credential) const
  {
    auto body = credential_body(credential);
    uint8_t digest[SHA256_DIGEST_LENGTH];
    SHA256(body.data(), body.size(), digest);
    std::unique_ptr<ECDSA_SIG, decltype(&ECDSA_SIG_free)> signature(
      ECDSA_do_sign(digest, sizeof(digest), key.get()), ECDSA_SIG_free);
    if (!signature) throw std::runtime_error("Credential signing failed");
    const BIGNUM* r;
    const BIGNUM* s;
    ECDSA_SIG_get0(signature.get(), &r, &s);
    credential.auth_signature.resize(64);
    if (!BN_bn2bin_padded(credential.auth_signature.data(), 32, r) ||
        !BN_bn2bin_padded(credential.auth_signature.data() + 32, 32, s)) {
      throw std::runtime_error("Credential signature encoding failed");
    }
    return encode_credential(credential);
  }

  DeviceCredential credential(const MlsEngine& engine, const std::string& user, const std::string& device) const
  {
    const auto now = credential_time_ms();
    return {policy.tenant_id, policy.app_id, user, device,
      engine.initialize_device().mls_signature_public_key, {3}, now - 1000, now + 86400000, "auth-1", {}};
  }

  MlsEngine device(const std::string& user, const std::string& device_id) const
  {
    MlsEngine engine(policy);
    engine.install_credential(sign(credential(engine, user, device_id)));
    return engine;
  }
};
}

TEST(CredentialTest, RejectsUntrustedExpiredSubstitutedAndNoncanonicalCredentials)
{
  Issuer issuer;
  MlsEngine engine(issuer.policy);
  auto credential = issuer.credential(engine, "alice", "alice-phone");
  const auto encoded = issuer.sign(credential);
  auto checked = verify_credential(encoded, issuer.policy, credential.signature_key, suite, credential_time_ms());
  EXPECT_TRUE(checked.device_id == credential.device_id);
  auto policy = issuer.policy;
  policy.tenant_id = "other";
  EXPECT_THROW(verify_credential(encoded, policy, credential.signature_key, suite, credential_time_ms()), Error);
  policy = issuer.policy;
  policy.app_id = "other";
  EXPECT_THROW(verify_credential(encoded, policy, credential.signature_key, suite, credential_time_ms()), Error);
  policy = issuer.policy;
  policy.auth_keys.clear();
  EXPECT_THROW(verify_credential(encoded, policy, credential.signature_key, suite, credential_time_ms()), Error);
  EXPECT_THROW(verify_credential(encoded, issuer.policy, Bytes(32), suite, credential_time_ms()), Error);
  EXPECT_THROW(verify_credential(encoded, issuer.policy, credential.signature_key,
    MlsCipherSuite::X25519_AES128GCM_SHA256_Ed25519, credential_time_ms()), Error);
  EXPECT_THROW(verify_credential(encoded, issuer.policy, credential.signature_key, suite, credential.expires_at_ms), Error);
  EXPECT_THROW(verify_credential(encoded, issuer.policy, credential.signature_key, suite, credential.created_at_ms - 1), Error);
  auto altered = encoded;
  altered.back() ^= 1;
  EXPECT_THROW(engine.install_credential(altered), Error);
  altered = encoded;
  altered.push_back(0);
  EXPECT_THROW(decode_credential(altered), Error);
  altered = encoded;
  altered.pop_back();
  EXPECT_THROW(decode_credential(altered), Error);
  EXPECT_THROW(engine.generate_key_packages(1), Error);
  engine.install_credential(encoded);
  EXPECT_NO_THROW(engine.generate_key_packages(1));
  EXPECT_THROW(engine.install_credential(encoded), Error);
}

TEST(CredentialTest, VerifiedDevicesPersistPendingCommitsRosterHistoryAndLastResortPackages)
{
  Issuer issuer;
  auto alice = issuer.device("alice", "alice-phone");
  auto bob = issuer.device("bob", "bob-phone");
  alice.set_members("chat", {"alice", "bob"});
  bob.set_members("chat", {"alice", "bob"});
  auto fallback = bob.last_resort_key_package();
  auto created = alice.prepare_create("chat", {fallback.key_package});
  EXPECT_THROW(MlsEngine::restore(alice.export_snapshot()), Error);
  alice = MlsEngine::restore(alice.export_snapshot(), issuer.policy);
  EXPECT_TRUE(alice.pending_commit("chat").commit == created.commit);
  alice.accept_pending("chat", created.commit);
  bob.join_from_welcome("chat", created.welcome);
  EXPECT_EQ(alice.roster("chat").size(), 2U);
  const auto old = alice.seal("chat", {}, {1, 2, 3});
  const auto ring = bob.export_key_ring();
  EXPECT_THROW(SealedKeyRing::restore(ring), Error);
  auto reader = SealedKeyRing::restore(ring, &issuer.policy);
  EXPECT_TRUE(reader.open("chat", {}, old).authenticated);
  auto wrong = issuer.policy;
  wrong.tenant_id = "other";
  EXPECT_THROW(SealedKeyRing::restore(ring, &wrong), Error);
  auto rotated = alice.prepare_rotate("chat");
  alice.accept_pending("chat", rotated.commit);
  bob.process_handshake("chat", rotated.commit);
  bob = MlsEngine::restore(bob.export_snapshot(), issuer.policy);
  EXPECT_TRUE(bob.last_resort_key_package().key_package == fallback.key_package);
  EXPECT_TRUE(bob.open("chat", {}, old).plaintext == Bytes({1, 2, 3}));
  EXPECT_THROW(alice.prepare_create("other", {fallback.key_package}), Error);
  alice.set_members("other", {"alice", "bob"});
  bob.set_members("other", {"alice", "bob"});
  auto other = alice.prepare_create("other", {fallback.key_package});
  alice.accept_pending("other", other.commit);
  EXPECT_NO_THROW(bob.join_from_welcome("other", other.welcome));
}

TEST(CredentialTest, AuthoritativeMembershipRefusesForeignDevicesAndExternalJoinsAtomically)
{
  Issuer issuer;
  auto alice = issuer.device("alice", "alice-phone");
  auto bob = issuer.device("bob", "bob-phone");
  auto carol = issuer.device("carol", "carol-phone");
  alice.set_members("chat", {"alice", "bob"});
  bob.set_members("chat", {"alice", "bob"});
  carol.set_members("chat", {"alice", "bob"});
  const auto created = alice.prepare_create("chat", {bob.generate_key_packages(1).front().key_package});
  alice.accept_pending("chat", created.commit);
  bob.join_from_welcome("chat", created.welcome);
  const auto before = carol.export_snapshot();
  EXPECT_THROW(carol.prepare_external_join("chat", created.group_info), Error);
  EXPECT_TRUE(carol.export_snapshot() == before);
  const auto carol_package = carol.generate_key_packages(1).front().key_package;
  const auto alice_before = alice.export_snapshot();
  EXPECT_THROW(alice.prepare_add("chat", {carol_package}), Error);
  EXPECT_TRUE(alice.export_snapshot() == alice_before);
  auto bob_tablet = issuer.device("bob", "bob-tablet");
  bob_tablet.set_members("chat", {"alice", "bob"});
  auto joined = bob_tablet.prepare_external_join("chat", created.group_info);
  bob_tablet = MlsEngine::restore(bob_tablet.export_snapshot(), issuer.policy);
  bob_tablet.accept_pending("chat", joined.commit);
  alice.process_handshake("chat", joined.commit);
  bob.process_handshake("chat", joined.commit);
  EXPECT_EQ(alice.roster("chat").size(), 3U);
  alice.set_members("chat", {"alice"});
  EXPECT_THROW(alice.seal("chat", {}, {1}), Error);
}


TEST(CredentialTest, VerifiedPolicyCredentialAndRosterCrossTheCAbi)
{
  Issuer issuer;
  auto view = [](const std::string& value) {
    return rsec_buffer_t{reinterpret_cast<uint8_t*>(const_cast<char*>(value.data())), value.size()};
  };
  const auto& pin = issuer.policy.auth_keys.begin()->second;
  const std::string key_id = "auth-1";
  rsec_auth_key_t key{view(key_id), {const_cast<uint8_t*>(pin.data()), pin.size()}};
  rsec_credential_policy_t policy{view(issuer.policy.tenant_id), view(issuer.policy.app_id), &key, 1};
  rsec_engine_t* raw = nullptr;
  ASSERT_EQ(rsec_engine_create_verified(&policy, 3, &raw), RSEC_OK);
  std::unique_ptr<rsec_engine_t, decltype(&rsec_engine_destroy)> device(raw, rsec_engine_destroy);
  rsec_buffer_t public_key{};
  ASSERT_EQ(rsec_device_public_key(device.get(), &public_key), RSEC_OK);
  MlsEngine metadata(issuer.policy);
  auto credential = issuer.credential(metadata, "alice", "alice-phone");
  credential.signature_key.assign(public_key.data, public_key.data + public_key.len);
  rsec_buffer_free(public_key);
  auto signed_credential = issuer.sign(credential);
  ASSERT_EQ(rsec_install_credential(device.get(), signed_credential.data(), signed_credential.size()), RSEC_OK);
  const std::string chat_id = "chat";
  const std::string user_id = "alice";
  auto member = view(user_id);
  ASSERT_EQ(rsec_set_members(device.get(), reinterpret_cast<const uint8_t*>(chat_id.data()), chat_id.size(), &member, 1), RSEC_OK);
  rsec_commit_t created{};
  ASSERT_EQ(rsec_prepare_create(device.get(), reinterpret_cast<const uint8_t*>(chat_id.data()), chat_id.size(), nullptr, 0, &created), RSEC_OK);
  rsec_buffer_t snapshot{};
  ASSERT_EQ(rsec_engine_snapshot(device.get(), &snapshot), RSEC_OK);
  device.reset();
  raw = nullptr;
  ASSERT_EQ(rsec_engine_restore_verified(&policy, snapshot.data, snapshot.len, &raw), RSEC_OK);
  device.reset(raw);
  rsec_buffer_free(snapshot);
  uint64_t epoch = 0;
  ASSERT_EQ(rsec_accept_pending(device.get(), reinterpret_cast<const uint8_t*>(chat_id.data()), chat_id.size(), created.commit.data, created.commit.len, &epoch), RSEC_OK);
  rsec_commit_free(created);
  size_t count = 0;
  ASSERT_EQ(rsec_roster_count(device.get(), reinterpret_cast<const uint8_t*>(chat_id.data()), chat_id.size(), &count), RSEC_OK);
  EXPECT_EQ(count, 1U);
  rsec_buffer_t encoded{};
  ASSERT_EQ(rsec_roster_credential(device.get(), reinterpret_cast<const uint8_t*>(chat_id.data()), chat_id.size(), 0, &encoded), RSEC_OK);
  auto checked = decode_credential(Bytes(encoded.data, encoded.data + encoded.len));
  EXPECT_TRUE(checked.device_id == credential.device_id && checked.signature_key == credential.signature_key);
  rsec_buffer_free(encoded);
  EXPECT_EQ(rsec_roster_credential(device.get(), reinterpret_cast<const uint8_t*>(chat_id.data()), chat_id.size(), 1, &encoded), RSEC_ERR_INVALID_ARGUMENT);
  EXPECT_EQ(encoded.data, nullptr);
}

TEST(CredentialTest, IncomingNonmemberCommitIsRejectedWithoutChangingAcceptedState)
{
  Issuer issuer;
  auto alice = issuer.device("alice", "alice-phone");
  auto bob = issuer.device("bob", "bob-phone");
  auto carol = issuer.device("carol", "carol-phone");
  alice.set_members("chat", {"alice", "bob"});
  bob.set_members("chat", {"alice", "bob"});
  auto created = alice.prepare_create("chat", {bob.generate_key_packages(1).front().key_package});
  alice.accept_pending("chat", created.commit);
  bob.join_from_welcome("chat", created.welcome);
  bob.set_members("chat", {"alice", "bob", "carol"});
  auto unauthorized = bob.prepare_add("chat", {carol.generate_key_packages(1).front().key_package});
  const auto before = alice.export_snapshot();
  EXPECT_THROW(alice.process_handshake("chat", unauthorized.commit), Error);
  EXPECT_TRUE(alice.export_snapshot() == before);
}
