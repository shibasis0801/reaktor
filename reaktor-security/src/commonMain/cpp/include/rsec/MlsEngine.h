#pragma once

#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace rsec {

using Bytes = std::vector<uint8_t>;
using ConversationId = std::string;

enum class MlsCipherSuite : uint16_t
{
  X25519_AES128GCM_SHA256_Ed25519 = 0x0001,
  P256_AES128GCM_SHA256_P256 = 0x0002,
  X25519_CHACHA20POLY1305_SHA256_Ed25519 = 0x0003,
};

enum class ErrorCode
{
  invalid_argument,
  crypto,
  mls,
  store,
  policy,
  conflict,
  unknown_group,
};

class Error : public std::runtime_error
{
public:
  Error(ErrorCode code, const std::string& message);
  ErrorCode code() const noexcept;

private:
  ErrorCode code_;
};

struct DeviceInitResult
{
  Bytes mls_signature_public_key;
  MlsCipherSuite cipher_suite;
};

struct KeyPackageRecord
{
  std::string key_package_id;
  Bytes key_package;
};

struct CommitResult
{
  Bytes welcome;
  Bytes commit;
  uint64_t epoch;
  Bytes group_info;
};

struct DecryptResult
{
  Bytes aad;
  Bytes plaintext;
};

enum class ContentPurpose : uint8_t
{
  message = 0,
  circle_game = 1,
};

struct SealedContent
{
  Bytes header;
  Bytes commitment;
  Bytes ciphertext;
};

struct OpenedContent
{
  Bytes plaintext;
  Bytes franking_key;
  Bytes sender_credential;
  bool authenticated;
  bool duplicate;
};

struct CredentialPolicy;
struct DeviceCredential;

class SealedKeyRing
{
public:
  SealedKeyRing(SealedKeyRing&&) noexcept;
  SealedKeyRing& operator=(SealedKeyRing&&) noexcept;
  ~SealedKeyRing();
  SealedKeyRing(const SealedKeyRing&) = delete;
  SealedKeyRing& operator=(const SealedKeyRing&) = delete;
  static SealedKeyRing restore(const Bytes& encoded, const CredentialPolicy* policy = nullptr);
  OpenedContent open(const ConversationId& id, const Bytes& envelope,
      const SealedContent& content, ContentPurpose purpose = ContentPurpose::message) const;
private:
  struct Impl;
  Impl* impl_;
  explicit SealedKeyRing(Impl* impl);
};

class MlsEngine
{
public:
  MlsEngine(Bytes device_credential,
            MlsCipherSuite suite =
              MlsCipherSuite::X25519_CHACHA20POLY1305_SHA256_Ed25519);
  MlsEngine(const CredentialPolicy& policy, MlsCipherSuite suite =
              MlsCipherSuite::X25519_CHACHA20POLY1305_SHA256_Ed25519);
  MlsEngine(MlsEngine&&) noexcept;
  MlsEngine& operator=(MlsEngine&&) noexcept;
  ~MlsEngine();

  MlsEngine(const MlsEngine&) = delete;
  MlsEngine& operator=(const MlsEngine&) = delete;

  DeviceInitResult initialize_device() const;
  Bytes prove_device(const std::string& user, const std::string& device) const;

  void install_credential(const Bytes& credential);
  void set_members(const ConversationId& id, const std::vector<std::string>& users);
  std::vector<DeviceCredential> roster(const ConversationId& id) const;
  std::vector<KeyPackageRecord> generate_key_packages(uint32_t count);
  KeyPackageRecord last_resort_key_package();
  CommitResult pending_commit(const ConversationId& id) const;

  CommitResult create_group(const ConversationId& conversation_id,
                            const std::vector<Bytes>& initial_members);

  uint64_t join_from_welcome(const ConversationId& conversation_id,
                             const Bytes& welcome);

  CommitResult add_members(const ConversationId& conversation_id,
                           const std::vector<Bytes>& new_member_key_packages);

  CommitResult remove_member_at(const ConversationId& conversation_id,
                                uint32_t roster_index);

  CommitResult rotate(const ConversationId& conversation_id);

  bool process_handshake(const ConversationId& conversation_id,
                         const Bytes& handshake_message);

  Bytes encrypt_application_message(const ConversationId& conversation_id,
                                    const Bytes& aad,
                                    const Bytes& plaintext);

  DecryptResult decrypt_application_message(const ConversationId& conversation_id,
                                            const Bytes& payload);

  uint64_t epoch(const ConversationId& conversation_id) const;

  Bytes export_snapshot() const;
  Bytes export_key_ring() const;
  static MlsEngine restore(const Bytes& snapshot);
  static MlsEngine restore(const Bytes& snapshot, const CredentialPolicy& policy);

  CommitResult prepare_create(const ConversationId& conversation_id,
                              const std::vector<Bytes>& members);
  CommitResult prepare_add(const ConversationId& conversation_id,
                           const std::vector<Bytes>& members);
  CommitResult prepare_remove(const ConversationId& conversation_id,
                              uint32_t roster_index);
  CommitResult prepare_membership(const ConversationId& conversation_id,
                                  const std::vector<Bytes>& additions,
                                  const std::vector<uint32_t>& removals);
  CommitResult prepare_rotate(const ConversationId& conversation_id);
  CommitResult prepare_external_join(const ConversationId& conversation_id,
                                     const Bytes& group_info);
  uint64_t accept_pending(const ConversationId& conversation_id,
                          const Bytes& accepted_commit);
  void discard_pending(const ConversationId& conversation_id);

  SealedContent seal(const ConversationId& conversation_id,
                     const Bytes& envelope, const Bytes& plaintext,
                     ContentPurpose purpose = ContentPurpose::message,
                     bool anonymous = false);
  OpenedContent open(const ConversationId& conversation_id,
                     const Bytes& envelope, const SealedContent& content,
                     ContentPurpose purpose = ContentPurpose::message);

private:
  static MlsEngine restore_engine(const Bytes& snapshot, const CredentialPolicy* policy);
  struct Impl;
  Impl* impl_;
};

} // namespace rsec
