#include <rsec/Credential.h>

#include <openssl/bn.h>
#include <openssl/ec_key.h>
#include <openssl/ecdsa.h>
#include <openssl/nid.h>
#include <openssl/sha.h>
#include <algorithm>
#include <chrono>
#include <memory>

namespace rsec {
namespace {
constexpr uint32_t credential_magic = 0x52534331;

void number(Bytes& out, uint64_t value, size_t size)
{
  for (size_t i = size; i > 0; --i) out.push_back(uint8_t(value >> (8 * (i - 1))));
}

void field(Bytes& out, const Bytes& value)
{
  if (value.empty() || value.size() > 4096) {
    throw Error(ErrorCode::invalid_argument, "Invalid credential field length");
  }
  number(out, value.size(), 4);
  out.insert(out.end(), value.begin(), value.end());
}

void field(Bytes& out, const std::string& value)
{
  if (!std::all_of(value.begin(), value.end(), [](unsigned char c) { return c >= 0x21 && c <= 0x7e; })) {
    throw Error(ErrorCode::invalid_argument, "Credential identifiers must be printable ASCII");
  }
  field(out, Bytes(value.begin(), value.end()));
}

class Reader
{
  const Bytes& bytes;
  size_t offset = 0;
public:
  explicit Reader(const Bytes& value) : bytes(value) {}
  uint64_t number(size_t size)
  {
    if (size > bytes.size() - offset) throw Error(ErrorCode::invalid_argument, "Truncated credential");
    uint64_t result = 0;
    while (size--) result = (result << 8) | bytes[offset++];
    return result;
  }
  Bytes take(size_t size)
  {
    if (size > bytes.size() - offset) throw Error(ErrorCode::invalid_argument, "Truncated credential");
    Bytes result(bytes.begin() + offset, bytes.begin() + offset + size);
    offset += size;
    return result;
  }
  Bytes field()
  {
    const auto size = number(4);
    if (size == 0 || size > 4096) throw Error(ErrorCode::invalid_argument, "Invalid credential field length");
    return take(size);
  }
  std::string text()
  {
    auto value = field();
    if (!std::all_of(value.begin(), value.end(), [](uint8_t c) { return c >= 0x21 && c <= 0x7e; })) {
      throw Error(ErrorCode::invalid_argument, "Invalid credential identifier");
    }
    return {value.begin(), value.end()};
  }
  bool empty() const { return offset == bytes.size(); }
};

bool verify_es256(const Bytes& public_key, const Bytes& body, const Bytes& signature)
{
  if (public_key.size() != 65 || public_key.front() != 4 || signature.size() != 64) return false;
  std::unique_ptr<EC_KEY, decltype(&EC_KEY_free)> key(EC_KEY_new_by_curve_name(NID_X9_62_prime256v1), EC_KEY_free);
  std::unique_ptr<ECDSA_SIG, decltype(&ECDSA_SIG_free)> sig(ECDSA_SIG_new(), ECDSA_SIG_free);
  std::unique_ptr<BIGNUM, decltype(&BN_free)> r(BN_bin2bn(signature.data(), 32, nullptr), BN_free);
  std::unique_ptr<BIGNUM, decltype(&BN_free)> s(BN_bin2bn(signature.data() + 32, 32, nullptr), BN_free);
  if (!key || !sig || !r || !s) throw std::bad_alloc();
  if (!EC_KEY_oct2key(key.get(), public_key.data(), public_key.size(), nullptr) ||
      !ECDSA_SIG_set0(sig.get(), r.get(), s.get())) return false;
  r.release();
  s.release();
  uint8_t digest[SHA256_DIGEST_LENGTH];
  SHA256(body.data(), body.size(), digest);
  return ECDSA_do_verify(digest, sizeof(digest), sig.get(), key.get()) == 1;
}
}

Bytes device_proof_body(const CredentialPolicy& policy, const std::string& user,
  const std::string& device, const Bytes& signature_key, MlsCipherSuite suite)
{
  Bytes result;
  number(result, 0x52535031, 4);
  number(result, 1, 2);
  field(result, policy.tenant_id);
  field(result, policy.app_id);
  field(result, user);
  field(result, device);
  field(result, signature_key);
  number(result, static_cast<uint16_t>(suite), 2);
  return result;
}

Bytes credential_body(const DeviceCredential& credential)
{
  Bytes out;
  number(out, credential_magic, 4);
  number(out, 1, 2);
  field(out, credential.tenant_id);
  field(out, credential.app_id);
  field(out, credential.user_id);
  field(out, credential.device_id);
  field(out, credential.signature_key);
  if (credential.cipher_suites.empty() || credential.cipher_suites.size() > 3 ||
      !std::is_sorted(credential.cipher_suites.begin(), credential.cipher_suites.end()) ||
      std::adjacent_find(credential.cipher_suites.begin(), credential.cipher_suites.end()) != credential.cipher_suites.end() ||
      credential.cipher_suites.front() < 1 || credential.cipher_suites.back() > 3) {
    throw Error(ErrorCode::invalid_argument, "Invalid credential cipher suites");
  }
  number(out, credential.cipher_suites.size(), 2);
  for (const auto suite : credential.cipher_suites) number(out, suite, 2);
  number(out, credential.created_at_ms, 8);
  number(out, credential.expires_at_ms, 8);
  field(out, credential.auth_key_id);
  return out;
}

Bytes encode_credential(const DeviceCredential& credential)
{
  if (credential.auth_signature.size() != 64) throw Error(ErrorCode::invalid_argument, "Invalid ES256 signature length");
  auto encoded = credential_body(credential);
  encoded.insert(encoded.end(), credential.auth_signature.begin(), credential.auth_signature.end());
  return encoded;
}

DeviceCredential decode_credential(const Bytes& encoded)
{
  if (encoded.empty() || encoded.size() > 32768) throw Error(ErrorCode::invalid_argument, "Invalid credential length");
  Reader in(encoded);
  if (in.number(4) != credential_magic || in.number(2) != 1) {
    throw Error(ErrorCode::invalid_argument, "Unsupported device credential");
  }
  DeviceCredential credential;
  credential.tenant_id = in.text();
  credential.app_id = in.text();
  credential.user_id = in.text();
  credential.device_id = in.text();
  credential.signature_key = in.field();
  const auto count = in.number(2);
  if (count == 0 || count > 3) throw Error(ErrorCode::invalid_argument, "Invalid credential cipher suite count");
  for (size_t i = 0; i < count; ++i) credential.cipher_suites.push_back(uint16_t(in.number(2)));
  credential.created_at_ms = in.number(8);
  credential.expires_at_ms = in.number(8);
  credential.auth_key_id = in.text();
  credential.auth_signature = in.take(64);
  if (!in.empty() || encode_credential(credential) != encoded) {
    throw Error(ErrorCode::invalid_argument, "Noncanonical device credential");
  }
  return credential;
}

DeviceCredential verify_credential(const Bytes& encoded, const CredentialPolicy& policy,
    const Bytes& signature_key, MlsCipherSuite suite, uint64_t now_ms)
{
  auto credential = decode_credential(encoded);
  const auto auth_key = policy.auth_keys.find(credential.auth_key_id);
  const auto suite_id = static_cast<uint16_t>(suite);
  if (credential.tenant_id != policy.tenant_id || credential.app_id != policy.app_id ||
      credential.signature_key != signature_key ||
      std::find(credential.cipher_suites.begin(), credential.cipher_suites.end(), suite_id) == credential.cipher_suites.end() ||
      credential.created_at_ms > now_ms || credential.expires_at_ms <= now_ms ||
      credential.expires_at_ms <= credential.created_at_ms ||
      credential.expires_at_ms - credential.created_at_ms > 366ULL * 24 * 60 * 60 * 1000 ||
      auth_key == policy.auth_keys.end() ||
      !verify_es256(auth_key->second, credential_body(credential), credential.auth_signature)) {
    throw Error(ErrorCode::policy, "Device credential verification failed");
  }
  return credential;
}

uint64_t credential_time_ms()
{
  return uint64_t(std::chrono::duration_cast<std::chrono::milliseconds>(
    std::chrono::system_clock::now().time_since_epoch()).count());
}

}
