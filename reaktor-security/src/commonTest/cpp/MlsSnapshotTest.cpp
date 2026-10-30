#include <gtest/gtest.h>
#include <mls/state.h>
#include <tls/tls_syntax.h>

namespace {
using namespace MLS_NAMESPACE;

bytes fixture_bytes(const std::string& value)
{
  return bytes(std::vector<uint8_t>(value.begin(), value.end()));
}

struct SnapshotDevice
{
  CipherSuite suite{CipherSuite::ID::X25519_CHACHA20POLY1305_SHA256_Ed25519};
  SignaturePrivateKey signature = SignaturePrivateKey::generate(suite);
  HPKEPrivateKey init = HPKEPrivateKey::generate(suite);
  HPKEPrivateKey leaf = HPKEPrivateKey::generate(suite);
  LeafNode node;
  KeyPackage package;

  explicit SnapshotDevice(const std::string& name)
    : node(suite, leaf.public_key, signature.public_key,
           Credential::basic(fixture_bytes(name)), Capabilities::create_default(),
           Lifetime::create_default(), {}, signature)
    , package(suite, init.public_key, node, {}, signature)
  {}

  State create() const
  {
    return State(fixture_bytes("snapshot-conversation"), suite, leaf, signature, node, {});
  }

  State join(const Welcome& welcome) const
  {
    return State(init, leaf, signature, package, welcome, std::nullopt, {});
  }
};

MessageOpts public_message()
{
  return MessageOpts{false, {}, 0};
}

CommitOpts public_commit(std::vector<Proposal> proposals = {})
{
  return CommitOpts{std::move(proposals), true, false, {}};
}

bytes content_key(const State& state)
{
  return state.do_export("reaktor/content/v1", state.group_id(), 32);
}
}

TEST(MlsSnapshotTest, RestoresExporterAndContinuesMembershipChanges)
{
  SnapshotDevice alice_device("alice"), bob_device("bob"), carol_device("carol");
  auto initial = alice_device.create();
  auto [commit, welcome, alice] = initial.commit(random_bytes(32),
    public_commit({initial.add_proposal(bob_device.package)}), public_message());
  auto bob = bob_device.join(welcome);
  const auto old_key = content_key(alice);
  alice = State::import_snapshot(alice.export_snapshot());
  bob = State::import_snapshot(bob.export_snapshot());
  EXPECT_EQ(content_key(alice), old_key);
  EXPECT_EQ(content_key(bob), old_key);
  auto [add, carol_welcome, next_alice] = alice.commit(random_bytes(32),
    public_commit({alice.add_proposal(carol_device.package)}), public_message());
  auto next_bob = bob.handle(add);
  ASSERT_TRUE(next_bob);
  auto carol = carol_device.join(carol_welcome);
  alice = State::import_snapshot(next_alice.export_snapshot());
  bob = State::import_snapshot(next_bob->export_snapshot());
  EXPECT_EQ(content_key(alice), content_key(bob));
  EXPECT_EQ(content_key(carol), content_key(bob));
  auto [remove, unused_welcome, final_alice] = alice.commit(random_bytes(32),
    public_commit({alice.remove_proposal(carol.index())}), public_message());
  auto final_bob = bob.handle(remove);
  ASSERT_TRUE(final_bob);
  EXPECT_EQ(content_key(final_alice), content_key(*final_bob));
  EXPECT_NE(content_key(final_alice), content_key(carol));
  EXPECT_EQ(final_alice.export_snapshot(),
    State::import_snapshot(final_alice.export_snapshot()).export_snapshot());
}

TEST(MlsSnapshotTest, RestoresPendingProposalCachedUpdateAndPsks)
{
  SnapshotDevice alice_device("alice"), bob_device("bob");
  auto initial = alice_device.create();
  auto [commit, welcome, alice] = initial.commit(random_bytes(32),
    public_commit({initial.add_proposal(bob_device.package)}), public_message());
  auto bob = bob_device.join(welcome);
  auto psk_id = fixture_bytes("external-psk");
  auto psk = random_bytes(32);
  alice.add_external_psk(psk_id, psk);
  bob.add_external_psk(psk_id, psk);
  alice.add_resumption_psk(fixture_bytes("prior-group"), 9, psk);
  bob.add_resumption_psk(fixture_bytes("prior-group"), 9, psk);
  auto update = bob.update(HPKEPrivateKey::generate(bob_device.suite), {}, public_message());
  ASSERT_NO_THROW({ EXPECT_FALSE(alice.handle(update)); });
  ASSERT_NO_THROW({ EXPECT_FALSE(bob.handle(update)); });
  ASSERT_NO_THROW(alice = State::import_snapshot(alice.export_snapshot()));
  ASSERT_NO_THROW(bob = State::import_snapshot(bob.export_snapshot()));
  ASSERT_NO_THROW(([&] {
    auto [next_commit, unused_welcome, next_alice] = alice.commit(random_bytes(32),
      public_commit({alice.pre_shared_key_proposal(psk_id),
                     alice.pre_shared_key_proposal(fixture_bytes("prior-group"), 9)}),
      public_message());
    auto next_bob = bob.handle(next_commit);
    ASSERT_TRUE(next_bob);
    EXPECT_EQ(content_key(next_alice), content_key(*next_bob));
  }()));
}

TEST(MlsSnapshotTest, RefusesMalformedUnsupportedAndInconsistentSnapshots)
{
  SnapshotDevice device("alice");
  auto state = device.create();
  const auto snapshot = state.export_snapshot();
  auto truncated = snapshot.as_vec();
  truncated.pop_back();
  EXPECT_ANY_THROW(State::import_snapshot(bytes(truncated)));
  auto bad_magic = snapshot.as_vec();
  bad_magic[0] ^= 1;
  EXPECT_ANY_THROW(State::import_snapshot(bytes(bad_magic)));
  auto future_version = snapshot.as_vec();
  future_version[5] = 2;
  EXPECT_ANY_THROW(State::import_snapshot(bytes(future_version)));
  auto appended = snapshot.as_vec();
  appended.push_back(0);
  EXPECT_ANY_THROW(State::import_snapshot(bytes(appended)));
  EXPECT_ANY_THROW(State::import_snapshot({}));
  EXPECT_EQ(snapshot, state.export_snapshot());
}

TEST(MlsSnapshotTest, RefusesToResetUsedPrivateMessageRatchets)
{
  SnapshotDevice alice_device("alice"), bob_device("bob");
  auto initial = alice_device.create();
  auto [commit, welcome, alice] = initial.commit(random_bytes(32),
    public_commit({initial.add_proposal(bob_device.package)}), public_message());
  auto bob = bob_device.join(welcome);
  auto encrypted = alice.protect({}, fixture_bytes("private-message-fixture"), 0);
  EXPECT_ANY_THROW(alice.export_snapshot());
  bob.unprotect(encrypted);
  EXPECT_ANY_THROW(bob.export_snapshot());
}
