#include <rsec/Attachment.h>

#include <mls/crypto.h>
#include <openssl/mem.h>
#include <openssl/sha.h>
#include <tls/tls_syntax.h>
#include <limits>
#include <memory>

namespace rsec {
namespace {
using namespace MLS_NAMESPACE;

CipherSuite suite()
{
  return CipherSuite(CipherSuite::ID::X25519_CHACHA20POLY1305_SHA256_Ed25519);
}

Bytes nonce(uint64_t index, bool last)
{
  Bytes result(12);
  for (size_t i = 0; i < 8; ++i) result[10 - i] = uint8_t(index >> (8 * i));
  result[11] = last ? 1 : 0;
  return result;
}

Bytes hash(const SHA256_CTX& context)
{
  auto copy = context;
  Bytes result(SHA256_DIGEST_LENGTH);
  SHA256_Final(result.data(), &copy);
  return result;
}

void require_key(const Bytes& key)
{
  if (key.size() != 32) throw Error(ErrorCode::invalid_argument, "A storage key must have 32 bytes");
}

struct Stream
{
  Bytes key;
  SHA256_CTX hash{};
  uint64_t index = 0;
  bool ended = false;
  explicit Stream(Bytes value) : key(std::move(value)) { SHA256_Init(&hash); }
  ~Stream() { OPENSSL_cleanse(key.data(), key.size()); }
  void advance(const Bytes& chunk, bool last)
  {
    SHA256_Update(&hash, chunk.data(), chunk.size());
    ended = last;
    if (!last) ++index;
  }
};

bytes open_aead(const Bytes& key, const bytes& iv, const bytes& aad, const bytes& ciphertext)
{
  try {
    auto plaintext = suite().hpke().aead.open(bytes(key), iv, aad, ciphertext);
    if (plaintext) return *plaintext;
  } catch (const std::bad_alloc&) {
    throw;
  } catch (const std::exception&) {
    throw Error(ErrorCode::crypto, "AEAD authentication failed");
  }
  throw Error(ErrorCode::crypto, "AEAD authentication failed");
}

constexpr size_t header_size = 17;
}

struct AttachmentSealer::Impl : Stream
{
  Impl() : Stream(random_bytes(32).as_vec()) {}
};

AttachmentSealer::AttachmentSealer() : impl_(new Impl) {}
AttachmentSealer::~AttachmentSealer() { delete impl_; }
Bytes AttachmentSealer::key() const { return impl_->key; }

Bytes AttachmentSealer::seal(const Bytes& plaintext, bool last)
{
  if (impl_->ended || plaintext.size() > 65536 || (!last && plaintext.size() != 65536) ||
      (!last && impl_->index == std::numeric_limits<uint64_t>::max())) {
    throw Error(ErrorCode::invalid_argument, "Invalid attachment chunk or stream state");
  }
  tls::ostream header;
  header << uint32_t(0x52534131) << uint8_t(last) << impl_->index << uint32_t(plaintext.size());
  const auto encoded = header.bytes();
  const auto ciphertext = suite().hpke().aead.seal(bytes(impl_->key),
    bytes(nonce(impl_->index, last)), bytes(encoded), bytes(plaintext));
  Bytes result(encoded.begin(), encoded.end());
  result.insert(result.end(), ciphertext.as_vec().begin(), ciphertext.as_vec().end());
  impl_->advance(result, last);
  return result;
}

Bytes AttachmentSealer::digest() const
{
  if (!impl_->ended) throw Error(ErrorCode::conflict, "Attachment has no authenticated final chunk");
  return hash(impl_->hash);
}

struct AttachmentOpener::Impl : Stream
{
  Bytes digest;
  Impl(const Bytes& key, const Bytes& expected) : Stream(key), digest(expected) {}
};

AttachmentOpener::AttachmentOpener(const Bytes& key, const Bytes& digest) : impl_(nullptr)
{
  require_key(key);
  if (digest.size() != 32) throw Error(ErrorCode::invalid_argument, "Invalid attachment digest");
  impl_ = new Impl(key, digest);
}
AttachmentOpener::~AttachmentOpener() { delete impl_; }

Bytes AttachmentOpener::open(const Bytes& chunk)
{
  if (impl_->ended || chunk.size() < header_size + 16 || chunk.size() > header_size + 65536 + 16) {
    throw Error(ErrorCode::invalid_argument, "Invalid attachment chunk or stream state");
  }
  tls::istream header(Bytes(chunk.begin(), chunk.begin() + header_size));
  uint32_t magic, length;
  uint64_t index;
  uint8_t last;
  header >> magic >> last >> index >> length;
  if (magic != 0x52534131 || last > 1 || index != impl_->index || length > 65536 ||
      chunk.size() != header_size + length + 16 || (!last && length != 65536) ||
      (!last && index == std::numeric_limits<uint64_t>::max())) {
    throw Error(ErrorCode::crypto, "Invalid attachment sequence");
  }
  auto plaintext = open_aead(impl_->key, bytes(nonce(index, last)),
    bytes(Bytes(chunk.begin(), chunk.begin() + header_size)),
    bytes(Bytes(chunk.begin() + header_size, chunk.end())));
  impl_->advance(chunk, last != 0);
  return plaintext.as_vec();
}

void AttachmentOpener::finish() const
{
  if (!impl_->ended || CRYPTO_memcmp(hash(impl_->hash).data(), impl_->digest.data(), 32) != 0) {
    throw Error(ErrorCode::crypto, "Attachment is truncated or has a different digest");
  }
}

Bytes storage_key() { return random_bytes(32).as_vec(); }

Bytes storage_seal(const Bytes& key, const Bytes& aad, const Bytes& plaintext)
{
  require_key(key);
  if (plaintext.size() > 64 * 1024 * 1024 || aad.size() > 65536) {
    throw Error(ErrorCode::invalid_argument, "Storage record exceeds its bound");
  }
  auto iv = random_bytes(12);
  auto ciphertext = suite().hpke().aead.seal(bytes(key), iv, bytes(aad), bytes(plaintext));
  Bytes result{1};
  result.insert(result.end(), iv.as_vec().begin(), iv.as_vec().end());
  result.insert(result.end(), ciphertext.as_vec().begin(), ciphertext.as_vec().end());
  return result;
}

Bytes storage_open(const Bytes& key, const Bytes& aad, const Bytes& ciphertext)
{
  require_key(key);
  if (ciphertext.size() < 29 || ciphertext.size() > 64 * 1024 * 1024 + 29 ||
      ciphertext.front() != 1 || aad.size() > 65536) {
    throw Error(ErrorCode::invalid_argument, "Invalid sealed storage record");
  }
  return open_aead(key, bytes(Bytes(ciphertext.begin() + 1, ciphertext.begin() + 13)),
    bytes(aad), bytes(Bytes(ciphertext.begin() + 13, ciphertext.end()))).as_vec();
}

}
