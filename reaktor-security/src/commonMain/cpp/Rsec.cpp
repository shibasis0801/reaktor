#include <rsec/rsec.h>

#include <rsec/MlsEngine.h>
#include <rsec/Attachment.h>
#include <rsec/Credential.h>

#include <cstdlib>
#include <cstring>
#include <memory>
#include <optional>
#include <string>
#include <vector>

struct rsec_engine
{
  std::unique_ptr<rsec::MlsEngine> impl;
};

struct rsec_key_ring
{
  rsec::SealedKeyRing impl;
  explicit rsec_key_ring(rsec::SealedKeyRing reader) : impl(std::move(reader)) {}
};

struct rsec_attachment_sealer { rsec::AttachmentSealer impl; };
struct rsec_attachment_opener
{
  rsec::AttachmentOpener impl;
  rsec_attachment_opener(const rsec::Bytes& key, const rsec::Bytes& digest) : impl(key, digest) {}
};

namespace {

rsec_status_t
map_error(const rsec::Error& error)
{
  switch (error.code()) {
    case rsec::ErrorCode::invalid_argument:
      return RSEC_ERR_INVALID_ARGUMENT;
    case rsec::ErrorCode::crypto:
      return RSEC_ERR_CRYPTO;
    case rsec::ErrorCode::mls:
      return RSEC_ERR_MLS;
    case rsec::ErrorCode::store:
      return RSEC_ERR_STORE;
    case rsec::ErrorCode::policy:
      return RSEC_ERR_POLICY;
    case rsec::ErrorCode::conflict:
      return RSEC_ERR_CONFLICT;
    case rsec::ErrorCode::unknown_group:
      return RSEC_ERR_UNKNOWN_GROUP;
  }

  return RSEC_ERR_UNKNOWN;
}

rsec::Bytes
copy_bytes(const uint8_t* data, size_t len)
{
  if (len > 64 * 1024 * 1024 + 4096 || (len > 0 && data == nullptr)) {
    throw rsec::Error(rsec::ErrorCode::invalid_argument,
                      "Non-empty buffer has null data");
  }
  if (len == 0) return {};
  return rsec::Bytes{ data, data + len };
}

std::string
conversation_id(const uint8_t* data, size_t len)
{
  if (len == 0 || len > 4096 || data == nullptr) {
    throw rsec::Error(rsec::ErrorCode::invalid_argument,
                      "conversation_id is required");
  }
  return std::string{ reinterpret_cast<const char*>(data), len };
}

rsec_status_t
copy_to_buffer(const rsec::Bytes& input, rsec_buffer_t* out)
{
  if (out == nullptr) {
    return RSEC_ERR_INVALID_ARGUMENT;
  }

  out->data = nullptr;
  out->len = input.size();

  if (input.empty()) {
    return RSEC_OK;
  }

  auto* ptr = static_cast<uint8_t*>(std::malloc(input.size()));
  if (ptr == nullptr) {
    return RSEC_ERR_STORE;
  }
  std::memcpy(ptr, input.data(), input.size());
  out->data = ptr;
  return RSEC_OK;
}

template<typename Fn>
rsec_status_t
guard(Fn&& fn)
{
  try {
    fn();
    return RSEC_OK;
  } catch (const rsec::Error& error) {
    return map_error(error);
  } catch (const std::bad_alloc&) {
    return RSEC_ERR_STORE;
  } catch (const std::invalid_argument&) {
    return RSEC_ERR_INVALID_ARGUMENT;
  } catch (const std::exception&) {
    return RSEC_ERR_MLS;
  } catch (...) {
    return RSEC_ERR_UNKNOWN;
  }
}


  void require_argument(bool condition)
  {
    if (!condition) throw rsec::Error(rsec::ErrorCode::invalid_argument, "Invalid native argument");
  }

  class OwnedBuffer
  {
  public:
    rsec_buffer_t value{};
    explicit OwnedBuffer(const rsec::Bytes& bytes)
    {
      if (copy_to_buffer(bytes, &value) != RSEC_OK) {
        throw rsec::Error(rsec::ErrorCode::store, "Unable to allocate output buffer");
      }
    }
    ~OwnedBuffer() { rsec_buffer_free(value); }
    rsec_buffer_t release()
    {
      const auto result = value;
      value = {};
      return result;
    }
  };


  rsec::SealedContent copy_content(const rsec_sealed_content_t* content)
  {
    require_argument(content && content->header.len <= 512 &&
      content->commitment.len == 32 && content->ciphertext.len <= 16 * 1024 * 1024 + 4096);
    return {copy_bytes(content->header.data, content->header.len),
      copy_bytes(content->commitment.data, content->commitment.len),
      copy_bytes(content->ciphertext.data, content->ciphertext.len)};
  }

  void copy_opened(const rsec::OpenedContent& opened, rsec_opened_content_t* out)
  {
    OwnedBuffer plaintext(opened.plaintext), franking(opened.franking_key),
      credential(opened.sender_credential);
    *out = {plaintext.release(), franking.release(), credential.release(),
      uint8_t(opened.authenticated), uint8_t(opened.duplicate)};
  }

  void copy_commit(const rsec::CommitResult& value, rsec_commit_t* out)
  {
    OwnedBuffer welcome(value.welcome), commit(value.commit), info(value.group_info);
    *out = {welcome.release(), commit.release(), info.release(), value.epoch};
  }

    rsec::CredentialPolicy copy_policy(const rsec_credential_policy_t* policy)
    {
      require_argument(policy && policy->auth_keys &&
        policy->auth_key_count > 0 && policy->auth_key_count <= 8);
      rsec::CredentialPolicy value{
        conversation_id(policy->tenant_id.data, policy->tenant_id.len),
        conversation_id(policy->app_id.data, policy->app_id.len), {}};
      for (size_t i = 0; i < policy->auth_key_count; ++i) {
        const auto& key = policy->auth_keys[i];
        require_argument(key.public_key.len == 65);
        require_argument(value.auth_keys.emplace(
          conversation_id(key.id.data, key.id.len),
          copy_bytes(key.public_key.data, key.public_key.len)).second);
      }
      return value;
    }

    std::vector<rsec::Bytes> copy_packages(const rsec_buffer_t* values, size_t count)
  {
    require_argument(count <= 4095 && (count == 0 || values));
    std::vector<rsec::Bytes> result;
    result.reserve(count);
    for (size_t i = 0; i < count; ++i) {
      require_argument(values[i].len <= 65536);
      result.push_back(copy_bytes(values[i].data, values[i].len));
    }
    return result;
  }

} // namespace

rsec_status_t
rsec_engine_create(const rsec_engine_config_t* config, rsec_engine_t** out)
{
  if (out) *out = nullptr;
  return guard([&] {
    if (config == nullptr || out == nullptr) {
      throw rsec::Error(rsec::ErrorCode::invalid_argument,
                        "config and out are required");
    }

    auto credential =
      copy_bytes(config->device_credential, config->device_credential_len);
    auto suite = static_cast<rsec::MlsCipherSuite>(config->cipher_suite);
    auto engine = std::make_unique<rsec_engine>();
    engine->impl = std::make_unique<rsec::MlsEngine>(std::move(credential), suite);
    *out = engine.release();
  });
}

void
rsec_engine_destroy(rsec_engine_t* engine)
{
  delete engine;
}

rsec_status_t
rsec_generate_key_package(rsec_engine_t* engine, rsec_buffer_t* out_key_package)
{
  if (out_key_package) *out_key_package = {};
  return guard([&] {
    if (engine == nullptr || out_key_package == nullptr) {
      throw rsec::Error(rsec::ErrorCode::invalid_argument,
                        "engine and out_key_package are required");
    }

    auto records = engine->impl->generate_key_packages(1);
    auto status = copy_to_buffer(records.front().key_package, out_key_package);
    if (status != RSEC_OK) {
      throw rsec::Error(rsec::ErrorCode::store, "Unable to allocate buffer");
    }
  });
}

rsec_status_t
rsec_create_group(rsec_engine_t* engine,
                  const uint8_t* conversation_id_data,
                  size_t conversation_id_len,
                  const rsec_buffer_t* peer_key_packages,
                  size_t peer_key_package_count,
                  rsec_buffer_t* out_welcome,
                  rsec_buffer_t* out_commit)
{
  if (out_welcome) *out_welcome = {};
  if (out_commit) *out_commit = {};
  return guard([&] {
    if (engine == nullptr || out_welcome == nullptr || out_commit == nullptr) {
      throw rsec::Error(rsec::ErrorCode::invalid_argument,
                        "engine and output buffers are required");
    }

    auto packages = copy_packages(peer_key_packages, peer_key_package_count);

    auto result = engine->impl->create_group(
      conversation_id(conversation_id_data, conversation_id_len), packages);
    OwnedBuffer welcome(result.welcome), commit(result.commit);
    *out_welcome = welcome.release();
    *out_commit = commit.release();
  });
}

rsec_status_t
rsec_join_group(rsec_engine_t* engine,
                const uint8_t* conversation_id_data,
                size_t conversation_id_len,
                const uint8_t* welcome,
                size_t welcome_len)
{
  return guard([&] {
    if (engine == nullptr) {
      throw rsec::Error(rsec::ErrorCode::invalid_argument,
                        "engine is required");
    }
    engine->impl->join_from_welcome(
      conversation_id(conversation_id_data, conversation_id_len),
      copy_bytes(welcome, welcome_len));
  });
}

rsec_status_t
rsec_process_handshake(rsec_engine_t* engine,
                       const uint8_t* conversation_id_data,
                       size_t conversation_id_len,
                       const uint8_t* handshake,
                       size_t handshake_len,
                       uint8_t* out_advanced)
{
  if (out_advanced) *out_advanced = 0;
  return guard([&] {
    if (engine == nullptr || out_advanced == nullptr) {
      throw rsec::Error(rsec::ErrorCode::invalid_argument,
                        "engine and out_advanced are required");
    }
    const auto advanced = engine->impl->process_handshake(
      conversation_id(conversation_id_data, conversation_id_len),
      copy_bytes(handshake, handshake_len));
    *out_advanced = advanced ? 1 : 0;
  });
}

rsec_status_t
rsec_encrypt_message(rsec_engine_t* engine,
                     const uint8_t* conversation_id_data,
                     size_t conversation_id_len,
                     const uint8_t* aad,
                     size_t aad_len,
                     const uint8_t* plaintext,
                     size_t plaintext_len,
                     rsec_buffer_t* out_payload)
{
  if (out_payload) *out_payload = {};
  return guard([&] {
    if (engine == nullptr || out_payload == nullptr) {
      throw rsec::Error(rsec::ErrorCode::invalid_argument,
                        "engine and out_payload are required");
    }
    auto payload = engine->impl->encrypt_application_message(
      conversation_id(conversation_id_data, conversation_id_len),
      copy_bytes(aad, aad_len),
      copy_bytes(plaintext, plaintext_len));
    auto status = copy_to_buffer(payload, out_payload);
    if (status != RSEC_OK) {
      throw rsec::Error(rsec::ErrorCode::store, "Unable to allocate buffer");
    }
  });
}

rsec_status_t
rsec_decrypt_message(rsec_engine_t* engine,
                     const uint8_t* conversation_id_data,
                     size_t conversation_id_len,
                     const uint8_t* payload,
                     size_t payload_len,
                     rsec_buffer_t* out_aad,
                     rsec_buffer_t* out_plaintext)
{
  if (out_aad) *out_aad = {};
  if (out_plaintext) *out_plaintext = {};
  return guard([&] {
    if (engine == nullptr || out_aad == nullptr || out_plaintext == nullptr) {
      throw rsec::Error(rsec::ErrorCode::invalid_argument,
                        "engine and output buffers are required");
    }
    auto decrypted = engine->impl->decrypt_application_message(
      conversation_id(conversation_id_data, conversation_id_len),
      copy_bytes(payload, payload_len));
    OwnedBuffer aad(decrypted.aad), plaintext(decrypted.plaintext);
    *out_aad = aad.release();
    *out_plaintext = plaintext.release();
  });
}



uint32_t rsec_abi_version(void) { return 2; }

rsec_status_t rsec_engine_create_verified(const rsec_credential_policy_t* policy,
    uint16_t suite, rsec_engine_t** out)
{
  if (out) *out = nullptr;
  return guard([&] {
    require_argument(out);
    auto engine = std::make_unique<rsec_engine>();
    engine->impl = std::make_unique<rsec::MlsEngine>(copy_policy(policy),
      static_cast<rsec::MlsCipherSuite>(suite));
    *out = engine.release();
  });
}

rsec_status_t rsec_engine_restore_verified(const rsec_credential_policy_t* policy,
    const uint8_t* snapshot, size_t len, rsec_engine_t** out)
{
  if (out) *out = nullptr;
  return guard([&] {
    require_argument(out && len > 0 && len <= 64 * 1024 * 1024);
    auto engine = std::make_unique<rsec_engine>();
    engine->impl = std::make_unique<rsec::MlsEngine>(
      rsec::MlsEngine::restore(copy_bytes(snapshot, len), copy_policy(policy)));
    *out = engine.release();
  });
}

rsec_status_t rsec_install_credential(rsec_engine_t* engine,
    const uint8_t* credential, size_t len)
{
  return guard([&] {
    require_argument(engine && len > 0 && len <= 32768);
    engine->impl->install_credential(copy_bytes(credential, len));
  });
}

rsec_status_t rsec_set_members(rsec_engine_t* engine, const uint8_t* id, size_t id_len,
    const rsec_buffer_t* users, size_t count)
{
  return guard([&] {
    require_argument(engine && users && count > 0 && count <= 4096);
    std::vector<std::string> members;
    members.reserve(count);
    for (size_t i = 0; i < count; ++i) members.push_back(conversation_id(users[i].data, users[i].len));
    engine->impl->set_members(conversation_id(id, id_len), members);
  });
}

rsec_status_t rsec_roster_count(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, size_t* out)
{
  if (out) *out = 0;
  return guard([&] {
    require_argument(engine && out);
    *out = engine->impl->roster(conversation_id(id, id_len)).size();
  });
}

rsec_status_t rsec_roster_credential(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, size_t index, rsec_buffer_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(engine && out);
    auto roster = engine->impl->roster(conversation_id(id, id_len));
    require_argument(index < roster.size());
    OwnedBuffer value(rsec::encode_credential(roster[index]));
    *out = value.release();
  });
}

rsec_status_t rsec_last_resort_key_package(rsec_engine_t* engine, rsec_buffer_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(engine && out);
    OwnedBuffer value(engine->impl->last_resort_key_package().key_package);
    *out = value.release();
  });
}

rsec_status_t rsec_pending_commit(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, rsec_commit_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(engine && out);
    copy_commit(engine->impl->pending_commit(conversation_id(id, id_len)), out);
  });
}

rsec_status_t
rsec_engine_snapshot(rsec_engine_t* engine, rsec_buffer_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(engine && out);
    OwnedBuffer value(engine->impl->export_snapshot());
    *out = value.release();
  });
}

rsec_status_t
rsec_engine_restore(const uint8_t* snapshot, size_t len, rsec_engine_t** out)
{
  if (out) *out = nullptr;
  return guard([&] {
    require_argument(out && len > 0 && len <= 64 * 1024 * 1024);
    auto restored = std::make_unique<rsec_engine>();
    restored->impl = std::make_unique<rsec::MlsEngine>(
      rsec::MlsEngine::restore(copy_bytes(snapshot, len)));
    *out = restored.release();
  });
}

rsec_status_t
rsec_device_public_key(rsec_engine_t* engine, rsec_buffer_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(engine && out);
    OwnedBuffer value(engine->impl->initialize_device().mls_signature_public_key);
    *out = value.release();
  });
}

rsec_status_t
rsec_device_proof(rsec_engine_t* engine, const uint8_t* user, size_t user_len,
    const uint8_t* device, size_t device_len, rsec_buffer_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(engine && out);
    OwnedBuffer value(engine->impl->prove_device(
      conversation_id(user, user_len), conversation_id(device, device_len)));
    *out = value.release();
  });
}

rsec_status_t
rsec_prepare_create(rsec_engine_t* engine, const uint8_t* id, size_t id_len,
                     const rsec_buffer_t* packages, size_t count, rsec_commit_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(engine && out);
    copy_commit(engine->impl->prepare_create(conversation_id(id, id_len),
      copy_packages(packages, count)), out);
  });
}

rsec_status_t
rsec_prepare_add(rsec_engine_t* engine, const uint8_t* id, size_t id_len,
                  const rsec_buffer_t* packages, size_t count, rsec_commit_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(engine && out);
    copy_commit(engine->impl->prepare_add(conversation_id(id, id_len),
      copy_packages(packages, count)), out);
  });
}

rsec_status_t
rsec_prepare_remove(rsec_engine_t* engine, const uint8_t* id, size_t id_len,
                     uint32_t index, rsec_commit_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(engine && out);
    copy_commit(engine->impl->prepare_remove(conversation_id(id, id_len), index), out);
  });
}

rsec_status_t
rsec_prepare_membership(rsec_engine_t* engine, const uint8_t* id, size_t id_len,
    const rsec_buffer_t* packages, size_t count, const uint8_t* removals, size_t len,
    rsec_commit_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(engine && out && len <= 4095 * 4 && len % 4 == 0 && (len == 0 || removals));
    std::vector<uint32_t> indices;
    for (size_t i = 0; i < len; i += 4) indices.push_back(
      (uint32_t(removals[i]) << 24) | (uint32_t(removals[i + 1]) << 16) |
      (uint32_t(removals[i + 2]) << 8) | uint32_t(removals[i + 3]));
    copy_commit(engine->impl->prepare_membership(conversation_id(id, id_len),
      copy_packages(packages, count), indices), out);
  });
}

rsec_status_t
rsec_prepare_rotate(rsec_engine_t* engine, const uint8_t* id, size_t id_len, rsec_commit_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(engine && out);
    copy_commit(engine->impl->prepare_rotate(conversation_id(id, id_len)), out);
  });
}

rsec_status_t
rsec_prepare_external_join(rsec_engine_t* engine, const uint8_t* id, size_t id_len,
                            const uint8_t* info, size_t info_len, rsec_commit_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(engine && out && info_len <= 16 * 1024 * 1024);
    copy_commit(engine->impl->prepare_external_join(conversation_id(id, id_len),
      copy_bytes(info, info_len)), out);
  });
}

rsec_status_t
rsec_accept_pending(rsec_engine_t* engine, const uint8_t* id, size_t id_len,
                      const uint8_t* commit, size_t len, uint64_t* out)
{
  if (out) *out = 0;
  return guard([&] {
    require_argument(engine && out && len <= 16 * 1024 * 1024);
    *out = engine->impl->accept_pending(conversation_id(id, id_len), copy_bytes(commit, len));
  });
}

rsec_status_t
rsec_discard_pending(rsec_engine_t* engine, const uint8_t* id, size_t id_len)
{
  return guard([&] {
    require_argument(engine);
    engine->impl->discard_pending(conversation_id(id, id_len));
  });
}

rsec_status_t
rsec_epoch(rsec_engine_t* engine, const uint8_t* id, size_t id_len, uint64_t* out)
{
  if (out) *out = 0;
  return guard([&] {
    require_argument(engine && out);
    *out = engine->impl->epoch(conversation_id(id, id_len));
  });
}

rsec_status_t
rsec_seal_content(rsec_engine_t* engine, const uint8_t* id, size_t id_len,
                   const uint8_t* envelope, size_t envelope_len,
                   const uint8_t* plaintext, size_t plaintext_len,
                   uint8_t purpose, uint8_t anonymous, rsec_sealed_content_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(engine && out && purpose <= 1 && anonymous <= 1 &&
      envelope_len <= 65536 && plaintext_len <= 16 * 1024 * 1024);
    auto content = engine->impl->seal(conversation_id(id, id_len),
      copy_bytes(envelope, envelope_len), copy_bytes(plaintext, plaintext_len),
      static_cast<rsec::ContentPurpose>(purpose), anonymous != 0);
    OwnedBuffer header(content.header), commitment(content.commitment), ciphertext(content.ciphertext);
    *out = {header.release(), commitment.release(), ciphertext.release()};
  });
}

rsec_status_t
rsec_open_content(rsec_engine_t* engine, const uint8_t* id, size_t id_len,
                   const uint8_t* envelope, size_t envelope_len,
                   const rsec_sealed_content_t* content, uint8_t purpose,
                   rsec_opened_content_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(engine && out && purpose <= 1 && envelope_len <= 65536);
    copy_opened(engine->impl->open(conversation_id(id, id_len),
      copy_bytes(envelope, envelope_len), copy_content(content),
      static_cast<rsec::ContentPurpose>(purpose)), out);
  });
}


rsec_status_t rsec_engine_key_ring(rsec_engine_t* engine, rsec_buffer_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(engine && out);
    OwnedBuffer value(engine->impl->export_key_ring());
    *out = value.release();
  });
}

rsec_status_t rsec_key_ring_create(const rsec_credential_policy_t* policy,
    const uint8_t* encoded, size_t len, rsec_key_ring_t** out)
{
  if (out) *out = nullptr;
  return guard([&] {
    require_argument(out && len > 0 && len <= 64 * 1024 * 1024);
    std::optional<rsec::CredentialPolicy> pinned;
    if (policy) pinned = copy_policy(policy);
    auto reader = rsec::SealedKeyRing::restore(copy_bytes(encoded, len), pinned ? &*pinned : nullptr);
    *out = new rsec_key_ring(std::move(reader));
  });
}

rsec_status_t rsec_key_ring_open(rsec_key_ring_t* ring,
    const uint8_t* id, size_t id_len, const uint8_t* envelope, size_t envelope_len,
    const rsec_sealed_content_t* content, uint8_t purpose, rsec_opened_content_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(ring && out && purpose <= 1 && envelope_len <= 65536);
    copy_opened(ring->impl.open(conversation_id(id, id_len),
      copy_bytes(envelope, envelope_len), copy_content(content),
      static_cast<rsec::ContentPurpose>(purpose)), out);
  });
}

void rsec_key_ring_destroy(rsec_key_ring_t* ring) { delete ring; }

void rsec_commit_free(rsec_commit_t value)
{
  rsec_buffer_free(value.welcome);
  rsec_buffer_free(value.commit);
  rsec_buffer_free(value.group_info);
}

void rsec_sealed_content_free(rsec_sealed_content_t value)
{
  rsec_buffer_free(value.header);
  rsec_buffer_free(value.commitment);
  rsec_buffer_free(value.ciphertext);
}

void rsec_opened_content_free(rsec_opened_content_t value)
{
  rsec_buffer_free(value.plaintext);
  rsec_buffer_free(value.franking_key);
  rsec_buffer_free(value.sender_credential);
}

rsec_status_t rsec_attachment_sealer_create(rsec_attachment_sealer_t** out)
{
  if (out) *out = nullptr;
  return guard([&] {
    require_argument(out);
    *out = new rsec_attachment_sealer;
  });
}

rsec_status_t rsec_attachment_key(rsec_attachment_sealer_t* stream, rsec_buffer_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(stream && out);
    OwnedBuffer value(stream->impl.key());
    *out = value.release();
  });
}

rsec_status_t rsec_attachment_seal(rsec_attachment_sealer_t* stream,
    const uint8_t* plaintext, size_t len, uint8_t last, rsec_buffer_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(stream && out && len <= 65536 && last <= 1);
    OwnedBuffer value(stream->impl.seal(copy_bytes(plaintext, len), last != 0));
    *out = value.release();
  });
}

rsec_status_t rsec_attachment_digest(rsec_attachment_sealer_t* stream, rsec_buffer_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(stream && out);
    OwnedBuffer value(stream->impl.digest());
    *out = value.release();
  });
}

void rsec_attachment_sealer_destroy(rsec_attachment_sealer_t* stream) { delete stream; }

rsec_status_t rsec_attachment_opener_create(const uint8_t* key, size_t key_len,
    const uint8_t* digest, size_t digest_len, rsec_attachment_opener_t** out)
{
  if (out) *out = nullptr;
  return guard([&] {
    require_argument(out && key_len == 32 && digest_len == 32);
    *out = new rsec_attachment_opener(copy_bytes(key, key_len), copy_bytes(digest, digest_len));
  });
}

rsec_status_t rsec_attachment_open(rsec_attachment_opener_t* stream,
    const uint8_t* chunk, size_t len, rsec_buffer_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(stream && out && len <= 65536 + 33);
    OwnedBuffer value(stream->impl.open(copy_bytes(chunk, len)));
    *out = value.release();
  });
}

rsec_status_t rsec_attachment_finish(rsec_attachment_opener_t* stream)
{
  return guard([&] { require_argument(stream); stream->impl.finish(); });
}

void rsec_attachment_opener_destroy(rsec_attachment_opener_t* stream) { delete stream; }

rsec_status_t rsec_storage_key(rsec_buffer_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(out);
    OwnedBuffer value(rsec::storage_key());
    *out = value.release();
  });
}

rsec_status_t rsec_storage_seal(const uint8_t* key, size_t key_len,
    const uint8_t* aad, size_t aad_len, const uint8_t* plaintext, size_t len, rsec_buffer_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(out && key_len == 32 && aad_len <= 65536 && len <= 64 * 1024 * 1024);
    OwnedBuffer value(rsec::storage_seal(copy_bytes(key, key_len),
      copy_bytes(aad, aad_len), copy_bytes(plaintext, len)));
    *out = value.release();
  });
}

rsec_status_t rsec_storage_open(const uint8_t* key, size_t key_len,
    const uint8_t* aad, size_t aad_len, const uint8_t* ciphertext, size_t len, rsec_buffer_t* out)
{
  if (out) *out = {};
  return guard([&] {
    require_argument(out && key_len == 32 && aad_len <= 65536 && len <= 64 * 1024 * 1024 + 29);
    OwnedBuffer value(rsec::storage_open(copy_bytes(key, key_len),
      copy_bytes(aad, aad_len), copy_bytes(ciphertext, len)));
    *out = value.release();
  });
}

void
rsec_buffer_free(rsec_buffer_t buffer)
{
  if (buffer.data == nullptr) {
    return;
  }
  volatile uint8_t* ptr = buffer.data;
  for (size_t i = 0; i < buffer.len; ++i) {
    ptr[i] = 0;
  }
  std::free(buffer.data);
}
