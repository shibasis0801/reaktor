#include <rsec/MlsEngine.h>
#include <rsec/Credential.h>

#include <mls/credential.h>
#include <mls/messages.h>
#include <mls/state.h>
#include <tls/tls_syntax.h>

#include <memory>
#include <map>
#include <set>
#include <algorithm>
#include <openssl/mem.h>
#include <optional>
#include <unordered_map>

namespace rsec {

namespace {

using namespace MLS_NAMESPACE;

CipherSuite
to_mls_suite(MlsCipherSuite suite)
{
  switch (suite) {
    case MlsCipherSuite::X25519_AES128GCM_SHA256_Ed25519:
      return CipherSuite{
        CipherSuite::ID::X25519_AES128GCM_SHA256_Ed25519
      };
    case MlsCipherSuite::P256_AES128GCM_SHA256_P256:
      return CipherSuite{ CipherSuite::ID::P256_AES128GCM_SHA256_P256 };
    case MlsCipherSuite::X25519_CHACHA20POLY1305_SHA256_Ed25519:
      return CipherSuite{
        CipherSuite::ID::X25519_CHACHA20POLY1305_SHA256_Ed25519
      };
  }

  throw Error(ErrorCode::invalid_argument, "Unsupported MLS cipher suite");
}

bytes
to_mls_bytes(const Bytes& input)
{
  return bytes{ input };
}

bytes
to_mls_bytes(const std::string& input)
{
  return bytes{ std::vector<uint8_t>{ input.begin(), input.end() } };
}

Bytes
from_mls_bytes(const bytes& input)
{
  return input.as_vec();
}

struct EpochContentKey
{
  bytes key;
  std::vector<LeafNode> roster;
  std::set<bytes> opened;
};
using ContentKeyRing = std::map<ConversationId, std::map<uint64_t, EpochContentKey>>;

struct PendingKeyPackage
{
  HPKEPrivateKey init_priv;
  HPKEPrivateKey leaf_priv;
  KeyPackage key_package;
};

LeafNode
create_leaf_node(CipherSuite suite,
                 const HPKEPrivateKey& leaf_priv,
                 const SignaturePrivateKey& identity_priv,
                 const Credential& credential)
{
  return LeafNode{ suite,
                   leaf_priv.public_key,
                   identity_priv.public_key,
                   credential,
                   Capabilities::create_default(),
                   Lifetime::create_default(),
                   {},
                   identity_priv };
}

MessageOpts
message_opts(const Bytes& aad)
{
  return MessageOpts{ false, to_mls_bytes(aad), 0 };
}

CommitOpts
commit_opts(std::vector<Proposal> proposals)
{
  return CommitOpts{ std::move(proposals), true, false, {} };
}

} // namespace

Error::Error(ErrorCode code, const std::string& message)
  : std::runtime_error(message)
  , code_(code)
{
}

ErrorCode
Error::code() const noexcept
{
  return code_;
}

struct MlsEngine::Impl
{
  CipherSuite suite;
  Bytes device_credential;
  Credential credential;
  SignaturePrivateKey identity_priv;
  uint64_t next_key_package_id{ 1 };
  std::unordered_map<std::string, PendingKeyPackage> pending_key_packages;
  std::unordered_map<ConversationId, State> groups;
  struct PendingCommit
  {
    State state;
    CommitResult result;
  };
  using EpochKey = EpochContentKey;
  std::optional<CredentialPolicy> policy;
  std::string last_resort_id;
  std::map<ConversationId, std::set<std::string>> members;
  std::map<std::string, Bytes> verified_credentials;

  DeviceCredential verify(const LeafNode& leaf) const
  {
    if (!policy || leaf.credential.type() != CredentialType::basic) {
      throw Error(ErrorCode::policy, "A verified basic device credential is required");
    }
    return verify_credential(leaf.credential.get<BasicCredential>().identity.as_vec(),
      *policy, leaf.signature_key.data.as_vec(),
      static_cast<MlsCipherSuite>(uint16_t(suite.cipher_suite())), credential_time_ms());
  }

  std::vector<DeviceCredential> validate(const ConversationId& id, const State& state) const
  {
    std::vector<DeviceCredential> result;
    if (!policy) return result;
    const auto allowed = members.find(id);
    if (allowed == members.end()) throw Error(ErrorCode::policy, "Conversation membership is required");
    std::set<std::string> devices;
    for (const auto& leaf : state.roster()) {
      auto device = verify(leaf);
      if (!allowed->second.count(device.user_id) || !devices.insert(device.device_id).second) {
        throw Error(ErrorCode::policy, "Roster contains a nonmember or duplicate device");
      }
      result.push_back(std::move(device));
    }
    return result;
  }

  void require_credential() const
  {
    if (policy) verify_credential(device_credential, *policy, identity_priv.public_key.data.as_vec(),
      static_cast<MlsCipherSuite>(uint16_t(suite.cipher_suite())), credential_time_ms());
  }

  KeyPackage package(const ConversationId& id, const Bytes& encoded) const
  {
    auto value = tls::get<KeyPackage>(to_mls_bytes(encoded));
    if (value.cipher_suite != suite || !value.verify()) {
      throw Error(ErrorCode::policy, "Invalid peer key package");
    }
    if (policy) {
      const auto device = verify(value.leaf_node);
      const auto allowed = members.find(id);
      if (allowed == members.end() || !allowed->second.count(device.user_id)) {
        throw Error(ErrorCode::policy, "Peer device user is not a conversation member");
      }
    }
    return value;
  }

  std::map<ConversationId, PendingCommit> pending_commits;
  ContentKeyRing key_ring;

  void remember(const ConversationId& id, const State& state)
  {
    const auto devices = validate(id, state);
    auto& epochs = key_ring[id];
    for (const auto& device : devices) verified_credentials.insert_or_assign(device.device_id, encode_credential(device));
    const auto key = state.do_export("reaktor/content/v1", to_mls_bytes(id), 32);
    auto found = epochs.find(state.epoch());
    if (found != epochs.end()) {
      if (found->second.key != key) {
        throw Error(ErrorCode::conflict, "Conflicting content key for accepted epoch");
      }
      return;
    }
    epochs.emplace(state.epoch(), EpochKey{key, state.roster(), {}});
  }

  void require_no_pending(const ConversationId& id) const
  {
    if (pending_commits.count(id)) {
      throw Error(ErrorCode::conflict, "Conversation already has a pending commit");
    }
  }

  CommitResult prepare(const ConversationId& id, State state,
                       const std::vector<Proposal>& proposals)
  {
    require_no_pending(id);
    auto [commit, welcome, next] = state.commit(random_bytes(suite.secret_size()),
      commit_opts(proposals), message_opts({}));
    validate(id, next);
    CommitResult result{from_mls_bytes(tls::marshal(welcome)),
      from_mls_bytes(tls::marshal(commit)), next.epoch(),
      from_mls_bytes(tls::marshal(next.group_info(true)))};
    pending_commits.emplace(id, PendingCommit{std::move(next), result});
    return result;
  }

  Impl(Bytes device_credential_in, MlsCipherSuite suite_in)
    : suite(to_mls_suite(suite_in))
    , device_credential(std::move(device_credential_in))
    , credential(Credential::basic(to_mls_bytes(device_credential)))
    , identity_priv(SignaturePrivateKey::generate(suite))
  {
  }

  State& group(const ConversationId& conversation_id)
  {
    auto found = groups.find(conversation_id);
    if (found == groups.end()) {
      throw Error(ErrorCode::unknown_group, "Unknown secure conversation");
    }
    return found->second;
  }

  const State& group(const ConversationId& conversation_id) const
  {
    auto found = groups.find(conversation_id);
    if (found == groups.end()) {
      throw Error(ErrorCode::unknown_group, "Unknown secure conversation");
    }
    return found->second;
  }
};

MlsEngine::MlsEngine(Bytes device_credential, MlsCipherSuite suite)
  : impl_(new Impl(std::move(device_credential), suite))
{
}

MlsEngine::MlsEngine(const CredentialPolicy& policy, MlsCipherSuite suite)
  : MlsEngine(Bytes{}, suite)
{
  if (policy.tenant_id.empty() || policy.app_id.empty() ||
      policy.auth_keys.empty() || policy.auth_keys.size() > 8) {
    throw Error(ErrorCode::invalid_argument, "Invalid pinned credential policy");
  }
  impl_->policy = policy;
}

MlsEngine::MlsEngine(MlsEngine&& other) noexcept
  : impl_(other.impl_)
{
  other.impl_ = nullptr;
}

MlsEngine&
MlsEngine::operator=(MlsEngine&& other) noexcept
{
  if (this != &other) {
    delete impl_;
    impl_ = other.impl_;
    other.impl_ = nullptr;
  }
  return *this;
}

MlsEngine::~MlsEngine()
{
  delete impl_;
}

DeviceInitResult
MlsEngine::initialize_device() const
{
  return DeviceInitResult{ from_mls_bytes(impl_->identity_priv.public_key.data),
                           static_cast<MlsCipherSuite>(
                             static_cast<uint16_t>(impl_->suite.cipher_suite())) };
}

Bytes
MlsEngine::prove_device(const std::string& user, const std::string& device) const
{
  if (!impl_->policy) throw Error(ErrorCode::policy, "Device proof requires credential policy");
  const auto identity = initialize_device();
  const auto body = device_proof_body(*impl_->policy, user, device,
    identity.mls_signature_public_key, identity.cipher_suite);
  const auto key = impl_->suite.sig().deserialize_private(impl_->identity_priv.data);
  return from_mls_bytes(impl_->suite.sig().sign(to_mls_bytes(body), *key));
}

std::vector<KeyPackageRecord>
MlsEngine::generate_key_packages(uint32_t count)
{
  impl_->require_credential();
  if (count == 0 || count > 256 || impl_->pending_key_packages.size() + count > 4096) {
    throw Error(ErrorCode::invalid_argument,
                "At least one key package must be requested");
  }

  std::vector<KeyPackageRecord> records;
  records.reserve(count);

  for (uint32_t i = 0; i < count; ++i) {
    auto init_priv = HPKEPrivateKey::generate(impl_->suite);
    auto leaf_priv = HPKEPrivateKey::generate(impl_->suite);
    auto leaf_node = create_leaf_node(
      impl_->suite, leaf_priv, impl_->identity_priv, impl_->credential);
    auto key_package =
      KeyPackage{ impl_->suite, init_priv.public_key, leaf_node, {}, impl_->identity_priv };

    auto id = "kp-" + std::to_string(impl_->next_key_package_id++);
    auto encoded = tls::marshal(key_package);
    impl_->pending_key_packages.emplace(
      id, PendingKeyPackage{ init_priv, leaf_priv, key_package });
    records.push_back(KeyPackageRecord{ id, from_mls_bytes(encoded) });
  }

  return records;
}

CommitResult
MlsEngine::create_group(const ConversationId& conversation_id,
                        const std::vector<Bytes>& initial_members)
{
  auto result = prepare_create(conversation_id, initial_members);
  accept_pending(conversation_id, result.commit);
  return result;
}

uint64_t
MlsEngine::join_from_welcome(const ConversationId& conversation_id,
                             const Bytes& welcome_bytes)
{
  if (impl_->groups.find(conversation_id) != impl_->groups.end()) {
    throw Error(ErrorCode::conflict, "Secure conversation already exists");
  }

  auto welcome = tls::get<Welcome>(to_mls_bytes(welcome_bytes));
  std::optional<std::string> matched_id;
  std::optional<State> matched_state;

  for (const auto& entry : impl_->pending_key_packages) {
    const auto& pending = entry.second;
    if (!welcome.find(pending.key_package).has_value()) {
      continue;
    }

    matched_state.emplace(pending.init_priv,
                          pending.leaf_priv,
                          impl_->identity_priv,
                          pending.key_package,
                          welcome,
                          std::nullopt,
                          std::map<bytes, bytes>{});
    matched_id = entry.first;
    break;
  }

  if (!matched_state || !matched_id) {
    throw Error(ErrorCode::mls,
                "Welcome did not match any locally sealed key package");
  }

  if (matched_state->group_id() != to_mls_bytes(conversation_id)) {
    throw Error(ErrorCode::policy, "Welcome belongs to a different conversation");
  }
  const auto epoch = matched_state->epoch();
  impl_->remember(conversation_id, *matched_state);
  impl_->groups.emplace(conversation_id, std::move(*matched_state));
  if (*matched_id != impl_->last_resort_id) impl_->pending_key_packages.erase(*matched_id);
  return epoch;
}

CommitResult
MlsEngine::add_members(const ConversationId& conversation_id,
                       const std::vector<Bytes>& new_member_key_packages)
{
  auto result = prepare_add(conversation_id, new_member_key_packages);
  accept_pending(conversation_id, result.commit);
  return result;
}

CommitResult
MlsEngine::remove_member_at(const ConversationId& conversation_id,
                            uint32_t roster_index)
{
  auto result = prepare_remove(conversation_id, roster_index);
  accept_pending(conversation_id, result.commit);
  return result;
}

CommitResult
MlsEngine::rotate(const ConversationId& conversation_id)
{
  auto result = prepare_rotate(conversation_id);
  accept_pending(conversation_id, result.commit);
  return result;
}

bool
MlsEngine::process_handshake(const ConversationId& conversation_id,
                             const Bytes& handshake_message)
{
  auto& state = impl_->group(conversation_id);
  auto candidate = state;
  auto msg = tls::get<MLSMessage>(to_mls_bytes(handshake_message));
  auto maybe_next = candidate.handle(msg);
  if (!maybe_next.has_value()) {
    state = std::move(candidate);
    return false;
  }

  impl_->remember(conversation_id, *maybe_next);
  impl_->pending_commits.erase(conversation_id);
  state = std::move(*maybe_next);
  return true;
}

Bytes
MlsEngine::encrypt_application_message(const ConversationId& conversation_id,
                                       const Bytes& aad,
                                       const Bytes& plaintext)
{
  auto& state = impl_->group(conversation_id);
  auto msg = state.protect(to_mls_bytes(aad), to_mls_bytes(plaintext), 0);
  return from_mls_bytes(tls::marshal(msg));
}

DecryptResult
MlsEngine::decrypt_application_message(const ConversationId& conversation_id,
                                       const Bytes& payload)
{
  auto& state = impl_->group(conversation_id);
  auto msg = tls::get<MLSMessage>(to_mls_bytes(payload));
  auto [aad, plaintext] = state.unprotect(msg);
  return DecryptResult{ from_mls_bytes(aad), from_mls_bytes(plaintext) };
}

uint64_t
MlsEngine::epoch(const ConversationId& conversation_id) const
{
  return impl_->group(conversation_id).epoch();
}


CommitResult
MlsEngine::prepare_create(const ConversationId& id, const std::vector<Bytes>& members)
{
  if (id.empty() || id.size() > 4096 || members.size() > 4095) {
    throw Error(ErrorCode::invalid_argument, "Invalid conversation or member count");
  }
  impl_->require_no_pending(id);
  if (impl_->groups.count(id)) {
    throw Error(ErrorCode::conflict, "Secure conversation already exists");
  }
  auto leaf = HPKEPrivateKey::generate(impl_->suite);
  auto node = create_leaf_node(impl_->suite, leaf, impl_->identity_priv, impl_->credential);
  impl_->require_credential();
  State state(to_mls_bytes(id), impl_->suite, leaf, impl_->identity_priv, node, {});
  std::vector<Proposal> proposals;
  for (const auto& encoded : members) proposals.push_back(state.add_proposal(impl_->package(id, encoded)));
  return impl_->prepare(id, std::move(state), proposals);
}

CommitResult
MlsEngine::prepare_add(const ConversationId& id, const std::vector<Bytes>& members)
{
  impl_->require_no_pending(id);
  if (members.empty() || members.size() > 4095) {
    throw Error(ErrorCode::invalid_argument, "Invalid member count");
  }
  auto state = impl_->group(id);
  std::vector<Proposal> proposals;
  for (const auto& encoded : members) proposals.push_back(state.add_proposal(impl_->package(id, encoded)));
  return impl_->prepare(id, std::move(state), proposals);
}

CommitResult
MlsEngine::prepare_remove(const ConversationId& id, uint32_t index)
{
  impl_->require_no_pending(id);
  auto state = impl_->group(id);
  auto proposal = state.remove_proposal(RosterIndex{index});
  return impl_->prepare(id, std::move(state), {proposal});
}

CommitResult
MlsEngine::prepare_membership(const ConversationId& id, const std::vector<Bytes>& additions,
                              const std::vector<uint32_t>& removals)
{
  impl_->require_no_pending(id);
  if (additions.size() > 4095 || removals.size() > 4095 ||
      (additions.empty() && removals.empty()) ||
      std::set<uint32_t>(removals.begin(), removals.end()).size() != removals.size()) {
    throw Error(ErrorCode::invalid_argument, "Invalid membership change");
  }
  auto state = impl_->group(id);
  std::vector<Proposal> proposals;
  for (const auto index : removals) proposals.push_back(state.remove_proposal(RosterIndex{index}));
  for (const auto& encoded : additions) proposals.push_back(state.add_proposal(impl_->package(id, encoded)));
  return impl_->prepare(id, std::move(state), proposals);
}

CommitResult
MlsEngine::prepare_rotate(const ConversationId& id)
{
  return impl_->prepare(id, impl_->group(id), {});
}

CommitResult
MlsEngine::prepare_external_join(const ConversationId& id, const Bytes& encoded)
{
  impl_->require_no_pending(id);
  if (impl_->groups.count(id)) {
    throw Error(ErrorCode::conflict, "Secure conversation already exists");
  }
  impl_->require_credential();
  auto info = tls::get<GroupInfo>(to_mls_bytes(encoded));
  auto init = HPKEPrivateKey::generate(impl_->suite);
  auto leaf = HPKEPrivateKey::generate(impl_->suite);
  KeyPackage package{impl_->suite, init.public_key,
    create_leaf_node(impl_->suite, leaf, impl_->identity_priv, impl_->credential), {}, impl_->identity_priv};
  auto [commit, next] = State::external_join(random_bytes(impl_->suite.secret_size()),
    impl_->identity_priv, package, info, std::nullopt, message_opts({}),
    std::nullopt, {});
  if (next.group_id() != to_mls_bytes(id) || next.cipher_suite() != impl_->suite) {
    throw Error(ErrorCode::policy, "Group info belongs to a different conversation or suite");
  }
  impl_->validate(id, next);
  CommitResult result{{}, from_mls_bytes(tls::marshal(commit)), next.epoch(),
    from_mls_bytes(tls::marshal(next.group_info(true)))};
  impl_->pending_commits.emplace(id, Impl::PendingCommit{std::move(next), result});
  return result;
}

uint64_t
MlsEngine::accept_pending(const ConversationId& id, const Bytes& accepted_commit)
{
  auto found = impl_->pending_commits.find(id);
  if (found == impl_->pending_commits.end() || found->second.result.commit != accepted_commit) {
    throw Error(ErrorCode::conflict, "Accepted commit does not match the prepared commit");
  }
  auto& pending = found->second;
  impl_->remember(id, pending.state);
  const auto next_epoch = pending.state.epoch();
  impl_->groups.insert_or_assign(id, std::move(pending.state));
  impl_->pending_commits.erase(found);
  return next_epoch;
}

void
MlsEngine::discard_pending(const ConversationId& id)
{
  impl_->pending_commits.erase(id);
}

void
MlsEngine::install_credential(const Bytes& encoded)
{
  if (!impl_->policy) throw Error(ErrorCode::policy, "A pinned auth policy is required");
  if (!impl_->groups.empty() || !impl_->pending_commits.empty() || !impl_->pending_key_packages.empty()) {
    throw Error(ErrorCode::conflict, "Install the device credential before creating key packages");
  }
  auto device = verify_credential(encoded, *impl_->policy,
    impl_->identity_priv.public_key.data.as_vec(),
    static_cast<MlsCipherSuite>(uint16_t(impl_->suite.cipher_suite())), credential_time_ms());
  impl_->device_credential = encoded;
  impl_->credential = Credential::basic(to_mls_bytes(encoded));
  impl_->verified_credentials.insert_or_assign(device.device_id, encoded);
}

void
MlsEngine::set_members(const ConversationId& id, const std::vector<std::string>& users)
{
  if (id.empty() || id.size() > 4096 || users.empty() || users.size() > 4096 ||
      std::any_of(users.begin(), users.end(), [](const std::string& user) {
        return user.empty() || user.size() > 4096;
      })) {
    throw Error(ErrorCode::invalid_argument, "Invalid authoritative conversation membership");
  }
  std::set<std::string> members(users.begin(), users.end());
  if (members.size() != users.size()) throw Error(ErrorCode::invalid_argument, "Duplicate member user");
  impl_->members.insert_or_assign(id, std::move(members));
}

std::vector<DeviceCredential>
MlsEngine::roster(const ConversationId& id) const
{
  std::vector<DeviceCredential> result;
  for (const auto& leaf : impl_->group(id).roster()) result.push_back(impl_->verify(leaf));
  return result;
}

KeyPackageRecord
MlsEngine::last_resort_key_package()
{
  impl_->require_credential();
  if (!impl_->last_resort_id.empty()) {
    return {impl_->last_resort_id, from_mls_bytes(tls::marshal(
      impl_->pending_key_packages.at(impl_->last_resort_id).key_package))};
  }
  auto package = generate_key_packages(1).front();
  impl_->last_resort_id = package.key_package_id;
  return package;
}

CommitResult
MlsEngine::pending_commit(const ConversationId& id) const
{
  const auto pending = impl_->pending_commits.find(id);
  if (pending == impl_->pending_commits.end()) throw Error(ErrorCode::conflict, "No pending conversation commit");
  return pending->second.result;
}

Bytes
MlsEngine::export_snapshot() const
{
  tls::ostream out;
  out << uint32_t(0x52534543) << uint16_t(3) << impl_->suite << bool(impl_->policy)
      << to_mls_bytes(impl_->device_credential) << impl_->identity_priv
      << impl_->next_key_package_id << to_mls_bytes(impl_->last_resort_id);
  out << uint32_t(impl_->pending_key_packages.size());
  std::map<std::string, PendingKeyPackage> packages(
    impl_->pending_key_packages.begin(), impl_->pending_key_packages.end());
  for (const auto& [id, package] : packages) {
    out << to_mls_bytes(id) << package.init_priv << package.leaf_priv << package.key_package;
  }
  std::map<ConversationId, State> groups(impl_->groups.begin(), impl_->groups.end());
  out << uint32_t(groups.size());
  for (const auto& [id, state] : groups) {
    out << to_mls_bytes(id) << state.export_snapshot();
  }
  out << uint32_t(impl_->pending_commits.size());
  for (const auto& [id, pending] : impl_->pending_commits) {
    out << to_mls_bytes(id) << pending.state.export_snapshot()
        << to_mls_bytes(pending.result.welcome) << to_mls_bytes(pending.result.commit)
        << to_mls_bytes(pending.result.group_info);
  }
  out << uint32_t(impl_->key_ring.size());
  for (const auto& [id, epochs] : impl_->key_ring) {
    out << to_mls_bytes(id) << uint32_t(epochs.size());
    for (const auto& [epoch, entry] : epochs) {
      out << epoch << entry.key << entry.roster << uint32_t(entry.opened.size());
      for (const auto& seal_id : entry.opened) out << seal_id;
    }
  }
  out << uint32_t(impl_->members.size());
  for (const auto& [id, users] : impl_->members) {
    out << to_mls_bytes(id) << uint32_t(users.size());
    for (const auto& user : users) out << to_mls_bytes(user);
  }
  out << uint32_t(impl_->verified_credentials.size());
  for (const auto& [id, credential] : impl_->verified_credentials) {
    out << to_mls_bytes(id) << to_mls_bytes(credential);
  }
  if (out.size() > 64 * 1024 * 1024) {
    throw Error(ErrorCode::store, "Engine snapshot exceeds its byte bound");
  }
  return out.bytes();
}

MlsEngine
MlsEngine::restore(const Bytes& snapshot)
{
  return restore_engine(snapshot, nullptr);
}

MlsEngine
MlsEngine::restore(const Bytes& snapshot, const CredentialPolicy& policy)
{
  return restore_engine(snapshot, &policy);
}

MlsEngine
MlsEngine::restore_engine(const Bytes& snapshot, const CredentialPolicy* policy)
{
  if (snapshot.empty() || snapshot.size() > 64 * 1024 * 1024) {
    throw Error(ErrorCode::invalid_argument, "Invalid engine snapshot length");
  }
  tls::istream in(snapshot);
  uint32_t magic;
  uint16_t version;
  CipherSuite suite;
  bytes credential;
  bool verified;
  in >> magic >> version >> suite >> verified >> credential;
  if (magic != 0x52534543 || version != 3) {
    throw Error(ErrorCode::invalid_argument, "Unsupported engine snapshot");
  }
  if (verified != bool(policy)) throw Error(ErrorCode::policy, "Snapshot credential policy is required and cannot be changed");
  auto suite_id = static_cast<MlsCipherSuite>(uint16_t(suite.cipher_suite()));
  auto engine = policy ? MlsEngine(*policy, suite_id) : MlsEngine(from_mls_bytes(credential), suite_id);
  auto& impl = *engine.impl_;
  impl.device_credential = credential.as_vec();
  impl.credential = Credential::basic(credential);
  bytes last_resort;
  in >> impl.identity_priv >> impl.next_key_package_id >> last_resort;
  impl.last_resort_id.assign(last_resort.as_vec().begin(), last_resort.as_vec().end());
  impl.identity_priv.set_public_key(suite);
  auto count = [&in]() {
    uint32_t value;
    in >> value;
    if (value > 65536) throw Error(ErrorCode::store, "Snapshot collection exceeds its bound");
    return value;
  };
  auto id = [&in]() {
    bytes value;
    in >> value;
    if (value.empty() || value.size() > 4096) {
      throw Error(ErrorCode::store, "Invalid snapshot identifier");
    }
    return std::string(value.as_vec().begin(), value.as_vec().end());
  };
  const auto package_count = count();
  if (package_count > 4096) throw Error(ErrorCode::store, "Too many key packages");
  for (uint32_t i = 0; i < package_count; ++i) {
    auto package_id = id();
    PendingKeyPackage package;
    in >> package.init_priv >> package.leaf_priv >> package.key_package;
    package.init_priv.set_public_key(suite);
    package.leaf_priv.set_public_key(suite);
    if (package.init_priv.public_key != package.key_package.init_key ||
        package.leaf_priv.public_key != package.key_package.leaf_node.encryption_key ||
        package.key_package.leaf_node.signature_key != impl.identity_priv.public_key ||
        !package.key_package.verify() ||
        !impl.pending_key_packages.emplace(package_id, std::move(package)).second) {
      throw Error(ErrorCode::store, "Inconsistent snapshot key package");
    }
  }
  auto read_state = [&]() {
    bytes encoded;
    in >> encoded;
    auto state = State::import_snapshot(encoded);
    if (state.cipher_suite() != suite ||
        state.tree().leaf_node(state.index())->signature_key != impl.identity_priv.public_key) {
      throw Error(ErrorCode::store, "Snapshot state has a different identity or suite");
    }
    return state;
  };
  const auto group_count = count();
  for (uint32_t i = 0; i < group_count; ++i) {
    auto group_id = id();
    auto state = read_state();
    if (state.group_id() != to_mls_bytes(group_id) ||
        !impl.groups.emplace(group_id, std::move(state)).second) {
      throw Error(ErrorCode::store, "Inconsistent snapshot conversation");
    }
  }
  const auto pending_count = count();
  for (uint32_t i = 0; i < pending_count; ++i) {
    auto group_id = id();
    auto state = read_state();
    bytes welcome, commit, info;
    in >> welcome >> commit >> info;
    const auto existing = impl.groups.find(group_id);
    if (state.group_id() != to_mls_bytes(group_id) ||
        (existing != impl.groups.end() && state.epoch() != existing->second.epoch() + 1) ||
        commit.empty()) {
      throw Error(ErrorCode::store, "Inconsistent snapshot pending commit");
    }
    auto saved_info = tls::get<GroupInfo>(info);
    auto restored_info = state.group_info(true);
    if (tls::marshal(saved_info.group_context) != tls::marshal(restored_info.group_context)) {
      throw Error(ErrorCode::store, "Snapshot pending group context mismatch");
    }
    const auto saved_external = saved_info.extensions.find<ExternalPubExtension>();
    const auto restored_external = restored_info.extensions.find<ExternalPubExtension>();
    const auto saved_tree = saved_info.extensions.find<RatchetTreeExtension>();
    const auto restored_tree = restored_info.extensions.find<RatchetTreeExtension>();
    for (const auto type : {ExternalPubExtension::type, RatchetTreeExtension::type}) {
      if (std::count_if(saved_info.extensions.extensions.begin(),
          saved_info.extensions.extensions.end(),
          [type](const Extension& extension) { return extension.type == type; }) != 1) {
        throw Error(ErrorCode::store, "Snapshot pending group extension is missing or duplicated");
      }
    }
    if (!saved_external || !saved_tree) {
      throw Error(ErrorCode::store, "Snapshot pending group extension is missing");
    }
    if (saved_external->external_pub != restored_external->external_pub) {
      throw Error(ErrorCode::store, "Snapshot pending external key mismatch");
    }
    if (tls::marshal(saved_tree->tree) != tls::marshal(restored_tree->tree)) {
      throw Error(ErrorCode::store, "Snapshot pending ratchet tree mismatch");
    }
    if (saved_info.confirmation_tag != restored_info.confirmation_tag ||
        saved_info.signer != restored_info.signer || !saved_info.verify(state.tree())) {
      throw Error(ErrorCode::store, "Snapshot pending group authentication mismatch");
    }
    const auto epoch = state.epoch();
    if (!impl.pending_commits.emplace(group_id, Impl::PendingCommit{std::move(state),
        {from_mls_bytes(welcome), from_mls_bytes(commit), epoch, from_mls_bytes(info)}}).second) {
      throw Error(ErrorCode::store, "Duplicate snapshot pending commit");
    }
  }
  const auto ring_count = count();
  for (uint32_t i = 0; i < ring_count; ++i) {
    auto group_id = id();
    if (impl.key_ring.count(group_id)) throw Error(ErrorCode::store, "Duplicate snapshot key ring");
    auto& epochs = impl.key_ring[group_id];
    const auto epoch_count = count();
    for (uint32_t j = 0; j < epoch_count; ++j) {
      uint64_t epoch;
      Impl::EpochKey entry;
      in >> epoch >> entry.key >> entry.roster;
      if (entry.key.size() != 32 || entry.roster.empty() || entry.roster.size() > 4096) {
        throw Error(ErrorCode::store, "Invalid snapshot epoch key");
      }
      const auto opened_count = count();
      for (uint32_t k = 0; k < opened_count; ++k) {
        bytes seal_id;
        in >> seal_id;
        if (seal_id.size() != 16 || !entry.opened.insert(seal_id).second) {
          throw Error(ErrorCode::store, "Invalid snapshot seal identifier");
        }
      }
      if (!epochs.emplace(epoch, std::move(entry)).second) {
        throw Error(ErrorCode::store, "Duplicate snapshot epoch");
      }
    }
  }
  const auto membership_count = count();
  for (uint32_t i = 0; i < membership_count; ++i) {
    auto group_id = id();
    const auto user_count = count();
    if (user_count == 0 || user_count > 4096 || impl.members.count(group_id)) {
      throw Error(ErrorCode::store, "Invalid snapshot membership");
    }
    auto& users = impl.members[group_id];
    for (uint32_t j = 0; j < user_count; ++j) {
      if (!users.insert(id()).second) throw Error(ErrorCode::store, "Duplicate snapshot member");
    }
  }
  const auto registry_count = count();
  for (uint32_t i = 0; i < registry_count; ++i) {
    auto device_id = id();
    bytes encoded;
    in >> encoded;
    if (!policy) throw Error(ErrorCode::store, "Unverified snapshot has a credential registry");
    auto device = decode_credential(encoded.as_vec());
    verify_credential(encoded.as_vec(), *policy, device.signature_key, suite_id, device.created_at_ms);
    if (device.device_id != device_id || !impl.verified_credentials.emplace(device_id, encoded.as_vec()).second) {
      throw Error(ErrorCode::store, "Inconsistent snapshot credential registry");
    }
  }
  if (!in.empty() || (!impl.last_resort_id.empty() &&
      !impl.pending_key_packages.count(impl.last_resort_id))) {
    throw Error(ErrorCode::store, "Invalid snapshot tail or last-resort package");
  }
  if (policy && !impl.device_credential.empty()) impl.require_credential();
  for (const auto& [group_id, pending] : impl.pending_commits) impl.validate(group_id, pending.state);
  for (const auto& [group_id, state] : impl.groups) {
    if (policy) {
      std::set<std::string> devices;
      for (const auto& leaf : state.roster()) {
        const auto device = impl.verify(leaf);
        const auto trusted = impl.verified_credentials.find(device.device_id);
        if (!devices.insert(device.device_id).second || trusted == impl.verified_credentials.end() ||
            trusted->second != leaf.credential.get<BasicCredential>().identity.as_vec()) {
          throw Error(ErrorCode::store, "Snapshot accepted roster has an unknown device");
        }
      }
    }
    const auto ring = impl.key_ring.find(group_id);
    if (ring == impl.key_ring.end() || !ring->second.count(state.epoch()) ||
        ring->second.at(state.epoch()).key != state.do_export(
          "reaktor/content/v1", to_mls_bytes(group_id), 32) ||
        tls::marshal(ring->second.at(state.epoch()).roster) != tls::marshal(state.roster())) {
      throw Error(ErrorCode::store, "Snapshot key ring does not match the accepted state");
    }
  }
  return engine;
}

namespace {
struct ContentHeader
{
  uint16_t version = 1;
  uint16_t suite = 0;
  uint64_t epoch = 0;
  bytes seal_id;
  uint8_t mode = 0;
  bytes sender;
  TLS_SERIALIZABLE(version, suite, epoch, seal_id, mode, sender)
};

std::pair<bytes, bytes>
content_material(CipherSuite suite, const bytes& epoch_key, const bytes& seal_id)
{
  auto info = to_mls_bytes("reaktor/seal/v1");
  info += seal_id;
  auto material = suite.hpke().kdf.expand(epoch_key, info, suite.key_size() + suite.nonce_size());
  const auto& data = material.as_vec();
  return {bytes(std::vector<uint8_t>(data.begin(), data.begin() + suite.key_size())),
    bytes(std::vector<uint8_t>(data.begin() + suite.key_size(), data.end()))};
}

bytes
content_aad(const Bytes& envelope, const Bytes& commitment, const Bytes& header)
{
  auto aad = to_mls_bytes(envelope);
  aad += to_mls_bytes(commitment);
  aad += to_mls_bytes(header);
  return aad;
}

bool
constant_equal(const bytes& left, const bytes& right)
{
  return left.size() == right.size() &&
    CRYPTO_memcmp(left.data(), right.data(), left.size()) == 0;
}

OpenedContent
open_key_ring(CipherSuite suite, ContentKeyRing& key_ring,
              const ConversationId& id, const Bytes& envelope,
              const SealedContent& content, ContentPurpose purpose, bool track_delivery)
{
  if (content.header.size() > 512 || content.commitment.size() != 32 ||
      content.ciphertext.size() > 16 * 1024 * 1024 + 4096 || envelope.size() > 65536) {
    throw Error(ErrorCode::invalid_argument, "Invalid sealed content length");
  }
  auto header = tls::get<ContentHeader>(to_mls_bytes(content.header));
  if (header.version != 1 || header.suite != uint16_t(suite.cipher_suite()) ||
      header.seal_id.size() != 16 || header.mode > 1 ||
      (header.mode == 1 && (purpose != ContentPurpose::circle_game || !header.sender.empty())) ||
      (header.mode == 0 && header.sender.empty())) {
    throw Error(ErrorCode::policy, "Unsupported content header or anonymous purpose");
  }
  auto ring = key_ring.find(id);
  if (ring == key_ring.end() || !ring->second.count(header.epoch)) {
    throw Error(ErrorCode::unknown_group, "No content key for this conversation epoch");
  }
  auto& entry = ring->second.at(header.epoch);
  const auto aad = content_aad(envelope, content.commitment, content.header);
  auto [key, nonce] = content_material(suite, entry.key, header.seal_id);
  auto opened = suite.hpke().aead.open(key, nonce, aad, to_mls_bytes(content.ciphertext));
  if (!opened) throw Error(ErrorCode::crypto, "Content authentication failed");
  tls::istream body(opened->as_vec());
  bytes franking_key, plaintext, signature, credential;
  body >> franking_key >> plaintext >> signature;
  if (!body.empty() || franking_key.size() != 32 ||
      !constant_equal(suite.digest().hmac(franking_key, plaintext),
                      to_mls_bytes(content.commitment))) {
    throw Error(ErrorCode::crypto, "Content commitment verification failed");
  }
  if (header.mode == 0) {
    const auto sender = std::find_if(entry.roster.begin(), entry.roster.end(),
      [&header](const LeafNode& leaf) { return leaf.signature_key.data == header.sender; });
    if (sender == entry.roster.end() ||
        !sender->signature_key.verify(suite, "reaktor/content/v1", aad, signature)) {
      throw Error(ErrorCode::crypto, "Content sender verification failed");
    }
    credential = sender->credential.get<BasicCredential>().identity;
  } else if (!signature.empty()) {
    throw Error(ErrorCode::crypto, "Anonymous content carries a signature");
  }
  const bool duplicate = track_delivery && !entry.opened.insert(header.seal_id).second;
  return {from_mls_bytes(plaintext), from_mls_bytes(franking_key),
    from_mls_bytes(credential), header.mode == 0, duplicate};
}
}

SealedContent
MlsEngine::seal(const ConversationId& id, const Bytes& envelope,
                const Bytes& plaintext, ContentPurpose purpose, bool anonymous)
{
  if (plaintext.size() > 16 * 1024 * 1024 || envelope.size() > 65536 ||
      (anonymous && purpose != ContentPurpose::circle_game)) {
    throw Error(ErrorCode::policy, "Invalid content size or anonymous purpose");
  }
  const auto& state = impl_->group(id);
  impl_->require_credential();
  impl_->validate(id, state);
  auto& entry = impl_->key_ring.at(id).at(state.epoch());
  ContentHeader header{1, uint16_t(impl_->suite.cipher_suite()), state.epoch(),
    random_bytes(16), uint8_t(anonymous ? 1 : 0),
    anonymous ? bytes{} : impl_->identity_priv.public_key.data};
  const auto encoded_header = tls::marshal(header);
  const auto franking_key = random_bytes(32);
  const auto commitment = impl_->suite.digest().hmac(franking_key, to_mls_bytes(plaintext));
  const auto aad = content_aad(envelope, from_mls_bytes(commitment), from_mls_bytes(encoded_header));
  bytes signature;
  if (!anonymous) signature = impl_->identity_priv.sign(impl_->suite, "reaktor/content/v1", aad);
  tls::ostream body;
  body << franking_key << to_mls_bytes(plaintext) << signature;
  auto [key, nonce] = content_material(impl_->suite, entry.key, header.seal_id);
  const auto ciphertext = impl_->suite.hpke().aead.seal(key, nonce, aad, bytes(body.bytes()));
  return {from_mls_bytes(encoded_header), from_mls_bytes(commitment), from_mls_bytes(ciphertext)};
}

OpenedContent
MlsEngine::open(const ConversationId& id, const Bytes& envelope,
                const SealedContent& content, ContentPurpose purpose)
{
  return open_key_ring(impl_->suite, impl_->key_ring, id, envelope, content, purpose, true);
}

struct SealedKeyRing::Impl
{
  CipherSuite suite;
  ContentKeyRing keys;
  explicit Impl(CipherSuite value) : suite(value) {}
};

SealedKeyRing::SealedKeyRing(Impl* impl) : impl_(impl) {}
SealedKeyRing::SealedKeyRing(SealedKeyRing&& other) noexcept : impl_(other.impl_) { other.impl_ = nullptr; }
SealedKeyRing& SealedKeyRing::operator=(SealedKeyRing&& other) noexcept
{
  if (this != &other) { delete impl_; impl_ = other.impl_; other.impl_ = nullptr; }
  return *this;
}
SealedKeyRing::~SealedKeyRing() { delete impl_; }

Bytes MlsEngine::export_key_ring() const
{
  tls::ostream out;
  out << uint32_t(0x52534b52) << uint16_t(1) << uint16_t(impl_->suite.cipher_suite())
      << bool(impl_->policy) << uint32_t(impl_->key_ring.size());
  for (const auto& [id, epochs] : impl_->key_ring) {
    out << to_mls_bytes(id) << uint32_t(epochs.size());
    for (const auto& [epoch, entry] : epochs) out << epoch << entry.key << entry.roster;
  }
  if (out.bytes().size() > 64 * 1024 * 1024) throw Error(ErrorCode::store, "Key ring exceeds its bound");
  return out.bytes();
}

SealedKeyRing SealedKeyRing::restore(const Bytes& encoded, const CredentialPolicy* policy)
{
  if (encoded.empty() || encoded.size() > 64 * 1024 * 1024) throw Error(ErrorCode::store, "Invalid key ring length");
  tls::istream in(encoded);
  uint32_t magic, groups;
  uint16_t version, suite_id;
  bool verified;
  in >> magic >> version >> suite_id >> verified >> groups;
  if (magic != 0x52534b52 || version != 1 || verified != bool(policy) || groups > 4096) {
    throw Error(ErrorCode::store, "Unsupported key ring or credential policy");
  }
  auto suite = to_mls_suite(static_cast<MlsCipherSuite>(suite_id));
  auto impl = std::make_unique<Impl>(suite);
  size_t total_epochs = 0;
  for (uint32_t i = 0; i < groups; ++i) {
    bytes group_id;
    uint32_t epoch_count;
    in >> group_id >> epoch_count;
    total_epochs += epoch_count;
    if (group_id.empty() || group_id.size() > 4096 || epoch_count > 65536 || total_epochs > 65536) {
      throw Error(ErrorCode::store, "Invalid key ring group");
    }
    const auto id = std::string(group_id.as_vec().begin(), group_id.as_vec().end());
    if (impl->keys.count(id)) throw Error(ErrorCode::store, "Duplicate key ring group");
    auto& epochs = impl->keys[id];
    for (uint32_t j = 0; j < epoch_count; ++j) {
      uint64_t epoch;
      EpochContentKey entry;
      in >> epoch >> entry.key >> entry.roster;
      if (entry.key.size() != 32 || entry.roster.empty() || entry.roster.size() > 4096) {
        throw Error(ErrorCode::store, "Invalid key ring epoch");
      }
      if (policy) {
        std::set<std::string> devices;
        for (const auto& leaf : entry.roster) {
          if (leaf.credential.type() != CredentialType::basic) throw Error(ErrorCode::policy, "A device credential is required");
          const auto bytes = leaf.credential.get<BasicCredential>().identity.as_vec();
          const auto device = decode_credential(bytes);
          verify_credential(bytes, *policy, leaf.signature_key.data.as_vec(),
            static_cast<MlsCipherSuite>(suite_id), device.created_at_ms);
          if (!devices.insert(device.device_id).second) throw Error(ErrorCode::policy, "Duplicate key ring device");
        }
      }
      if (!epochs.emplace(epoch, std::move(entry)).second) throw Error(ErrorCode::store, "Duplicate key ring epoch");
    }
  }
  if (!in.empty()) throw Error(ErrorCode::store, "Key ring has trailing input");
  return SealedKeyRing(impl.release());
}

OpenedContent SealedKeyRing::open(const ConversationId& id, const Bytes& envelope,
    const SealedContent& content, ContentPurpose purpose) const
{
  return open_key_ring(impl_->suite, impl_->keys, id, envelope, content, purpose, false);
}

} // namespace rsec
