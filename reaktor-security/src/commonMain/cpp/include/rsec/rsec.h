#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct rsec_engine rsec_engine_t;

typedef struct rsec_buffer {
  uint8_t* data;
  size_t len;
} rsec_buffer_t;

typedef struct rsec_engine_config {
  const uint8_t* device_credential;
  size_t device_credential_len;
  uint16_t cipher_suite;
} rsec_engine_config_t;

typedef enum rsec_status {
  RSEC_OK = 0,
  RSEC_ERR_INVALID_ARGUMENT = 1,
  RSEC_ERR_CRYPTO = 2,
  RSEC_ERR_MLS = 3,
  RSEC_ERR_STORE = 4,
  RSEC_ERR_POLICY = 5,
  RSEC_ERR_CONFLICT = 6,
  RSEC_ERR_UNKNOWN_GROUP = 7,
  RSEC_ERR_UNKNOWN = 255,
} rsec_status_t;

rsec_status_t rsec_engine_create(const rsec_engine_config_t* config,
                                 rsec_engine_t** out);

void rsec_engine_destroy(rsec_engine_t* engine);

rsec_status_t rsec_generate_key_package(rsec_engine_t* engine,
                                        rsec_buffer_t* out_key_package);

rsec_status_t rsec_create_group(rsec_engine_t* engine,
                                const uint8_t* conversation_id,
                                size_t conversation_id_len,
                                const rsec_buffer_t* peer_key_packages,
                                size_t peer_key_package_count,
                                rsec_buffer_t* out_welcome,
                                rsec_buffer_t* out_commit);

rsec_status_t rsec_join_group(rsec_engine_t* engine,
                              const uint8_t* conversation_id,
                              size_t conversation_id_len,
                              const uint8_t* welcome,
                              size_t welcome_len);

rsec_status_t rsec_process_handshake(rsec_engine_t* engine,
                                     const uint8_t* conversation_id,
                                     size_t conversation_id_len,
                                     const uint8_t* handshake,
                                     size_t handshake_len,
                                     uint8_t* out_advanced);

rsec_status_t rsec_encrypt_message(rsec_engine_t* engine,
                                   const uint8_t* conversation_id,
                                   size_t conversation_id_len,
                                   const uint8_t* aad,
                                   size_t aad_len,
                                   const uint8_t* plaintext,
                                   size_t plaintext_len,
                                   rsec_buffer_t* out_payload);

rsec_status_t rsec_decrypt_message(rsec_engine_t* engine,
                                   const uint8_t* conversation_id,
                                   size_t conversation_id_len,
                                   const uint8_t* payload,
                                   size_t payload_len,
                                   rsec_buffer_t* out_aad,
                                   rsec_buffer_t* out_plaintext);

void rsec_buffer_free(rsec_buffer_t buffer);

#ifdef __cplusplus
}
#endif
