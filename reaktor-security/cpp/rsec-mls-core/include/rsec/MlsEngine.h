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
};

struct DecryptResult
{
  Bytes aad;
  Bytes plaintext;
};

class MlsEngine
{
public:
  MlsEngine(Bytes device_credential,
            MlsCipherSuite suite =
              MlsCipherSuite::X25519_CHACHA20POLY1305_SHA256_Ed25519);
  MlsEngine(MlsEngine&&) noexcept;
  MlsEngine& operator=(MlsEngine&&) noexcept;
  ~MlsEngine();

  MlsEngine(const MlsEngine&) = delete;
  MlsEngine& operator=(const MlsEngine&) = delete;

  DeviceInitResult initialize_device() const;

  std::vector<KeyPackageRecord> generate_key_packages(uint32_t count);

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

private:
  struct Impl;
  Impl* impl_;
};

} // namespace rsec
