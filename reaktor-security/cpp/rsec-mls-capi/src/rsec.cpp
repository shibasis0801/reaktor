#include <rsec/rsec.h>

#include <rsec/MlsEngine.h>

#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

struct rsec_engine
{
  std::unique_ptr<rsec::MlsEngine> impl;
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
  if (len > 0 && data == nullptr) {
    throw rsec::Error(rsec::ErrorCode::invalid_argument,
                      "Non-empty buffer has null data");
  }
  return rsec::Bytes{ data, data + len };
}

std::string
conversation_id(const uint8_t* data, size_t len)
{
  if (len == 0 || data == nullptr) {
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
  } catch (const std::invalid_argument&) {
    return RSEC_ERR_INVALID_ARGUMENT;
  } catch (const std::exception&) {
    return RSEC_ERR_MLS;
  } catch (...) {
    return RSEC_ERR_UNKNOWN;
  }
}

} // namespace

rsec_status_t
rsec_engine_create(const rsec_engine_config_t* config, rsec_engine_t** out)
{
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
  return guard([&] {
    if (engine == nullptr || out_welcome == nullptr || out_commit == nullptr) {
      throw rsec::Error(rsec::ErrorCode::invalid_argument,
                        "engine and output buffers are required");
    }

    std::vector<rsec::Bytes> packages;
    packages.reserve(peer_key_package_count);
    for (size_t i = 0; i < peer_key_package_count; ++i) {
      packages.push_back(copy_bytes(peer_key_packages[i].data,
                                    peer_key_packages[i].len));
    }

    auto result = engine->impl->create_group(
      conversation_id(conversation_id_data, conversation_id_len), packages);
    auto welcome_status = copy_to_buffer(result.welcome, out_welcome);
    auto commit_status = copy_to_buffer(result.commit, out_commit);
    if (welcome_status != RSEC_OK || commit_status != RSEC_OK) {
      throw rsec::Error(rsec::ErrorCode::store, "Unable to allocate buffer");
    }
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
  return guard([&] {
    if (engine == nullptr || out_aad == nullptr || out_plaintext == nullptr) {
      throw rsec::Error(rsec::ErrorCode::invalid_argument,
                        "engine and output buffers are required");
    }
    auto decrypted = engine->impl->decrypt_application_message(
      conversation_id(conversation_id_data, conversation_id_len),
      copy_bytes(payload, payload_len));
    auto aad_status = copy_to_buffer(decrypted.aad, out_aad);
    auto plaintext_status = copy_to_buffer(decrypted.plaintext, out_plaintext);
    if (aad_status != RSEC_OK || plaintext_status != RSEC_OK) {
      throw rsec::Error(rsec::ErrorCode::store, "Unable to allocate buffer");
    }
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
