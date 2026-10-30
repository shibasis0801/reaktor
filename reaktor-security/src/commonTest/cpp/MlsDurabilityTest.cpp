#include <gtest/gtest.h>
#include <rsec/MlsEngine.h>

namespace {
rsec::Bytes data(const std::string& value)
{
  return {value.begin(), value.end()};
}

struct Pair
{
  rsec::MlsEngine alice{data("alice")};
  rsec::MlsEngine bob{data("bob")};
  Pair()
  {
    auto package = bob.generate_key_packages(1).front().key_package;
    auto commit = alice.prepare_create("chat", {package});
    EXPECT_THROW(alice.epoch("chat"), rsec::Error);
    alice.accept_pending("chat", commit.commit);
    bob.join_from_welcome("chat", commit.welcome);
  }
};
}

TEST(MlsDurabilityTest, RestartsWithIdentityPackagesPendingCommitsAndHistory)
{
  rsec::MlsEngine alice(data("alice"));
  rsec::MlsEngine bob(data("bob"));
  const auto identity = bob.initialize_device().mls_signature_public_key;
  auto package = bob.generate_key_packages(1).front().key_package;
  bob = rsec::MlsEngine::restore(bob.export_snapshot());
  EXPECT_EQ(identity, bob.initialize_device().mls_signature_public_key);
  auto created = alice.prepare_create("chat", {package});
  alice = rsec::MlsEngine::restore(alice.export_snapshot());
  EXPECT_THROW(alice.epoch("chat"), rsec::Error);
  EXPECT_THROW(alice.prepare_create("chat", {package}), rsec::Error);
  EXPECT_THROW(alice.accept_pending("chat", data("other")), rsec::Error);
  alice.accept_pending("chat", created.commit);
  bob.join_from_welcome("chat", created.welcome);
  const auto envelope = data("chat/message/alice");
  const auto old = alice.seal("chat", envelope, data("old content"));
  alice = rsec::MlsEngine::restore(alice.export_snapshot());
  bob = rsec::MlsEngine::restore(bob.export_snapshot());
  auto opened = bob.open("chat", envelope, old);
  EXPECT_EQ(opened.plaintext, data("old content"));
  EXPECT_TRUE(opened.authenticated);
  EXPECT_FALSE(opened.duplicate);
  bob = rsec::MlsEngine::restore(bob.export_snapshot());
  EXPECT_TRUE(bob.open("chat", envelope, old).duplicate);
  auto refresh = alice.prepare_rotate("chat");
  EXPECT_EQ(alice.epoch("chat"), 1U);
  alice.accept_pending("chat", refresh.commit);
  EXPECT_TRUE(bob.process_handshake("chat", refresh.commit));
  EXPECT_EQ(bob.open("chat", envelope, old).plaintext, data("old content"));
  auto reply = bob.seal("chat", data("reply"), data("new content"));
  EXPECT_EQ(alice.open("chat", data("reply"), reply).plaintext, data("new content"));
  rsec::MlsEngine carol(data("carol"));
  auto add = alice.prepare_add("chat", {carol.generate_key_packages(1).front().key_package});
  alice = rsec::MlsEngine::restore(alice.export_snapshot());
  alice.accept_pending("chat", add.commit);
  bob.process_handshake("chat", add.commit);
  carol.join_from_welcome("chat", add.welcome);
  EXPECT_THROW(carol.open("chat", envelope, old), rsec::Error);
  auto remove = alice.prepare_remove("chat", 2);
  alice.accept_pending("chat", remove.commit);
  bob.process_handshake("chat", remove.commit);
  auto after = bob.seal("chat", data("after remove"), data("only two"));
  EXPECT_THROW(carol.open("chat", data("after remove"), after), rsec::Error);
  alice = rsec::MlsEngine::restore(alice.export_snapshot());
  EXPECT_EQ(alice.open("chat", data("after remove"), after).plaintext, data("only two"));
}

TEST(MlsDurabilityTest, ConflictingCommitsConvergeWithoutAdvancingBeforeAcceptance)
{
  Pair pair;
  auto alice = pair.alice.prepare_rotate("chat");
  auto bob = pair.bob.prepare_rotate("chat");
  EXPECT_EQ(pair.alice.epoch("chat"), 1U);
  EXPECT_EQ(pair.bob.epoch("chat"), 1U);
  EXPECT_THROW(pair.alice.prepare_rotate("chat"), rsec::Error);
  pair.alice.accept_pending("chat", alice.commit);
  pair.bob.discard_pending("chat");
  pair.bob.process_handshake("chat", alice.commit);
  EXPECT_THROW(pair.bob.accept_pending("chat", bob.commit), rsec::Error);
  EXPECT_THROW(pair.bob.process_handshake("chat", bob.commit), std::exception);
  EXPECT_EQ(pair.bob.epoch("chat"), 2U);
  auto next = pair.alice.prepare_rotate("chat");
  pair.alice.accept_pending("chat", next.commit);
  auto later = pair.alice.prepare_rotate("chat");
  pair.alice.accept_pending("chat", later.commit);
  const auto before = pair.bob.export_snapshot();
  EXPECT_THROW(pair.bob.process_handshake("chat", later.commit), std::exception);
  EXPECT_EQ(before, pair.bob.export_snapshot());
  pair.bob.process_handshake("chat", next.commit);
  pair.bob.process_handshake("chat", later.commit);
  auto sealed = pair.bob.seal("chat", {}, data("converged"));
  EXPECT_EQ(pair.alice.open("chat", {}, sealed).plaintext, data("converged"));
}

TEST(MlsDurabilityTest, RejectsTamperingAndRestrictsAnonymousContent)
{
  Pair pair;
  const auto envelope = data("envelope");
  const auto sealed = pair.alice.seal("chat", envelope, data("body"));
  auto changed = sealed;
  changed.ciphertext.back() ^= 1;
  EXPECT_THROW(pair.bob.open("chat", envelope, changed), std::exception);
  changed = sealed;
  changed.header.back() ^= 1;
  EXPECT_THROW(pair.bob.open("chat", envelope, changed), std::exception);
  changed = sealed;
  changed.commitment.front() ^= 1;
  EXPECT_THROW(pair.bob.open("chat", envelope, changed), std::exception);
  EXPECT_THROW(pair.bob.open("chat", data("substituted envelope"), sealed), std::exception);
  EXPECT_FALSE(pair.bob.open("chat", envelope, sealed).duplicate);
  EXPECT_THROW(pair.alice.seal("chat", {}, {}, rsec::ContentPurpose::message, true), rsec::Error);
  auto answer = pair.alice.seal("chat", {}, data("anonymous"),
    rsec::ContentPurpose::circle_game, true);
  EXPECT_THROW(pair.bob.open("chat", {}, answer), rsec::Error);
  auto opened = pair.bob.open("chat", {}, answer, rsec::ContentPurpose::circle_game);
  EXPECT_EQ(opened.plaintext, data("anonymous"));
  EXPECT_FALSE(opened.authenticated);
  EXPECT_TRUE(opened.sender_credential.empty());
}

TEST(MlsDurabilityTest, SnapshotRejectsMalformedAndTrailingInput)
{
  Pair pair;
  auto snapshot = pair.alice.export_snapshot();
  auto bad = snapshot;
  bad.front() ^= 1;
  EXPECT_THROW(rsec::MlsEngine::restore(bad), std::exception);
  bad = snapshot;
  bad.pop_back();
  EXPECT_THROW(rsec::MlsEngine::restore(bad), std::exception);
  bad = snapshot;
  bad.push_back(0);
  EXPECT_THROW(rsec::MlsEngine::restore(bad), std::exception);
  EXPECT_EQ(pair.alice.export_snapshot(), snapshot);
}

TEST(MlsDurabilityTest, ExternalJoinContinuesAfterRestart)
{
  rsec::MlsEngine alice(data("alice"));
  auto created = alice.prepare_create("chat", {});
  alice.accept_pending("chat", created.commit);
  rsec::MlsEngine bob(data("bob"));
  auto joined = bob.prepare_external_join("chat", created.group_info);
  EXPECT_THROW(bob.epoch("chat"), rsec::Error);
  bob = rsec::MlsEngine::restore(bob.export_snapshot());
  bob.accept_pending("chat", joined.commit);
  EXPECT_TRUE(alice.process_handshake("chat", joined.commit));
  auto sealed = bob.seal("chat", {}, data("external join"));
  EXPECT_EQ(alice.open("chat", {}, sealed).plaintext, data("external join"));
}

