#pragma once

#include <rsec/MlsEngine.h>
#include <map>

namespace rsec {

struct CredentialPolicy
{
  std::string tenant_id;
  std::string app_id;
  std::map<std::string, Bytes> auth_keys;
};

struct DeviceCredential
{
  std::string tenant_id;
  std::string app_id;
  std::string user_id;
  std::string device_id;
  Bytes signature_key;
  std::vector<uint16_t> cipher_suites;
  uint64_t created_at_ms;
  uint64_t expires_at_ms;
  std::string auth_key_id;
  Bytes auth_signature;
};

Bytes device_proof_body(const CredentialPolicy& policy, const std::string& user,
  const std::string& device, const Bytes& signature_key, MlsCipherSuite suite);
Bytes credential_body(const DeviceCredential& credential);
Bytes encode_credential(const DeviceCredential& credential);
DeviceCredential decode_credential(const Bytes& encoded);
DeviceCredential verify_credential(const Bytes& encoded, const CredentialPolicy& policy,
  const Bytes& signature_key, MlsCipherSuite suite, uint64_t now_ms);
uint64_t credential_time_ms();

}
