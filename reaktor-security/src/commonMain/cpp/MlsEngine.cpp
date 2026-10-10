#include <rsec/MlsEngine.h>

#include <mls/credential.h>
#include <mls/messages.h>
#include <mls/state.h>
#include <tls/tls_syntax.h>

#include <memory>
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

std::vector<KeyPackageRecord>
MlsEngine::generate_key_packages(uint32_t count)
{
  if (count == 0) {
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
  if (impl_->groups.find(conversation_id) != impl_->groups.end()) {
    throw Error(ErrorCode::conflict, "Secure conversation already exists");
  }

  auto own_leaf_priv = HPKEPrivateKey::generate(impl_->suite);
  auto own_leaf_node = create_leaf_node(
    impl_->suite, own_leaf_priv, impl_->identity_priv, impl_->credential);
  auto state = State{ to_mls_bytes(conversation_id),
                      impl_->suite,
                      own_leaf_priv,
                      impl_->identity_priv,
                      own_leaf_node,
                      {} };

  if (initial_members.empty()) {
    const auto epoch = state.epoch();
    impl_->groups.emplace(conversation_id, std::move(state));
    return CommitResult{ {}, {}, epoch };
  }

  std::vector<Proposal> proposals;
  proposals.reserve(initial_members.size());
  for (const auto& key_package_bytes : initial_members) {
    auto key_package = tls::get<KeyPackage>(to_mls_bytes(key_package_bytes));
    proposals.push_back(state.add_proposal(key_package));
  }

  auto [commit, welcome, next_state] =
    state.commit(random_bytes(impl_->suite.secret_size()),
                 commit_opts(std::move(proposals)),
                 message_opts({}));

  const auto epoch = next_state.epoch();
  impl_->groups.emplace(conversation_id, std::move(next_state));
  return CommitResult{ from_mls_bytes(tls::marshal(welcome)),
                       from_mls_bytes(tls::marshal(commit)),
                       epoch };
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

  const auto epoch = matched_state->epoch();
  impl_->groups.emplace(conversation_id, std::move(*matched_state));
  impl_->pending_key_packages.erase(*matched_id);
  return epoch;
}

CommitResult
MlsEngine::add_members(const ConversationId& conversation_id,
                       const std::vector<Bytes>& new_member_key_packages)
{
  if (new_member_key_packages.empty()) {
    throw Error(ErrorCode::invalid_argument,
                "At least one new key package is required");
  }

  auto& state = impl_->group(conversation_id);
  std::vector<Proposal> proposals;
  proposals.reserve(new_member_key_packages.size());
  for (const auto& key_package_bytes : new_member_key_packages) {
    auto key_package = tls::get<KeyPackage>(to_mls_bytes(key_package_bytes));
    proposals.push_back(state.add_proposal(key_package));
  }

  auto [commit, welcome, next_state] =
    state.commit(random_bytes(impl_->suite.secret_size()),
                 commit_opts(std::move(proposals)),
                 message_opts({}));

  const auto epoch = next_state.epoch();
  state = std::move(next_state);
  return CommitResult{ from_mls_bytes(tls::marshal(welcome)),
                       from_mls_bytes(tls::marshal(commit)),
                       epoch };
}

CommitResult
MlsEngine::remove_member_at(const ConversationId& conversation_id,
                            uint32_t roster_index)
{
  auto& state = impl_->group(conversation_id);
  auto proposal = state.remove_proposal(RosterIndex{ roster_index });
  auto [commit, welcome, next_state] =
    state.commit(random_bytes(impl_->suite.secret_size()),
                 commit_opts({ proposal }),
                 message_opts({}));

  const auto epoch = next_state.epoch();
  state = std::move(next_state);
  return CommitResult{ from_mls_bytes(tls::marshal(welcome)),
                       from_mls_bytes(tls::marshal(commit)),
                       epoch };
}

CommitResult
MlsEngine::rotate(const ConversationId& conversation_id)
{
  auto& state = impl_->group(conversation_id);
  auto [commit, welcome, next_state] =
    state.commit(random_bytes(impl_->suite.secret_size()),
                 commit_opts({}),
                 message_opts({}));

  const auto epoch = next_state.epoch();
  state = std::move(next_state);
  return CommitResult{ from_mls_bytes(tls::marshal(welcome)),
                       from_mls_bytes(tls::marshal(commit)),
                       epoch };
}

bool
MlsEngine::process_handshake(const ConversationId& conversation_id,
                             const Bytes& handshake_message)
{
  auto& state = impl_->group(conversation_id);
  auto msg = tls::get<MLSMessage>(to_mls_bytes(handshake_message));
  auto maybe_next = state.handle(msg);
  if (!maybe_next.has_value()) {
    return false;
  }

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

} // namespace rsec
