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


typedef struct rsec_commit {
  rsec_buffer_t welcome;
  rsec_buffer_t commit;
  rsec_buffer_t group_info;
  uint64_t epoch;
} rsec_commit_t;

typedef struct rsec_sealed_content {
  rsec_buffer_t header;
  rsec_buffer_t commitment;
  rsec_buffer_t ciphertext;
} rsec_sealed_content_t;

typedef struct rsec_opened_content {
  rsec_buffer_t plaintext;
  rsec_buffer_t franking_key;
  rsec_buffer_t sender_credential;
  uint8_t authenticated;
  uint8_t duplicate;
} rsec_opened_content_t;


typedef struct rsec_auth_key {
  rsec_buffer_t id;
  rsec_buffer_t public_key;
} rsec_auth_key_t;

typedef struct rsec_credential_policy {
  rsec_buffer_t tenant_id;
  rsec_buffer_t app_id;
  const rsec_auth_key_t* auth_keys;
  size_t auth_key_count;
} rsec_credential_policy_t;

uint32_t rsec_abi_version(void);
rsec_status_t rsec_engine_create_verified(const rsec_credential_policy_t* policy,
    uint16_t cipher_suite, rsec_engine_t** out);
rsec_status_t rsec_engine_restore_verified(const rsec_credential_policy_t* policy,
    const uint8_t* snapshot, size_t snapshot_len, rsec_engine_t** out);
rsec_status_t rsec_install_credential(rsec_engine_t* engine,
    const uint8_t* credential, size_t credential_len);
rsec_status_t rsec_set_members(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, const rsec_buffer_t* users, size_t user_count);
rsec_status_t rsec_roster_count(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, size_t* out);
rsec_status_t rsec_roster_credential(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, size_t index, rsec_buffer_t* out);
rsec_status_t rsec_last_resort_key_package(rsec_engine_t* engine, rsec_buffer_t* out);
rsec_status_t rsec_pending_commit(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, rsec_commit_t* out);

typedef struct rsec_key_ring rsec_key_ring_t;
rsec_status_t rsec_engine_key_ring(rsec_engine_t* engine, rsec_buffer_t* out);
rsec_status_t rsec_key_ring_create(const rsec_credential_policy_t* policy,
    const uint8_t* encoded, size_t len, rsec_key_ring_t** out);
rsec_status_t rsec_key_ring_open(rsec_key_ring_t* ring,
    const uint8_t* id, size_t id_len, const uint8_t* envelope, size_t envelope_len,
    const rsec_sealed_content_t* content, uint8_t purpose, rsec_opened_content_t* out);
void rsec_key_ring_destroy(rsec_key_ring_t* ring);

rsec_status_t rsec_engine_snapshot(rsec_engine_t* engine, rsec_buffer_t* out);
rsec_status_t rsec_engine_restore(const uint8_t* snapshot, size_t snapshot_len,
                                 rsec_engine_t** out);
rsec_status_t rsec_device_public_key(rsec_engine_t* engine, rsec_buffer_t* out);
rsec_status_t rsec_device_proof(rsec_engine_t* engine,
    const uint8_t* user, size_t user_len, const uint8_t* device, size_t device_len,
    rsec_buffer_t* out);
rsec_status_t rsec_prepare_create(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, const rsec_buffer_t* packages,
    size_t package_count, rsec_commit_t* out);
rsec_status_t rsec_prepare_add(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, const rsec_buffer_t* packages,
    size_t package_count, rsec_commit_t* out);
rsec_status_t rsec_prepare_remove(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, uint32_t roster_index, rsec_commit_t* out);
rsec_status_t rsec_prepare_membership(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, const rsec_buffer_t* packages, size_t package_count,
    const uint8_t* removals, size_t removals_len, rsec_commit_t* out);
rsec_status_t rsec_prepare_rotate(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, rsec_commit_t* out);
rsec_status_t rsec_prepare_external_join(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, const uint8_t* info, size_t info_len,
    rsec_commit_t* out);
rsec_status_t rsec_accept_pending(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, const uint8_t* commit, size_t commit_len,
    uint64_t* out_epoch);
rsec_status_t rsec_discard_pending(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len);
rsec_status_t rsec_epoch(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, uint64_t* out_epoch);
rsec_status_t rsec_seal_content(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, const uint8_t* envelope, size_t envelope_len,
    const uint8_t* plaintext, size_t plaintext_len, uint8_t purpose,
    uint8_t anonymous, rsec_sealed_content_t* out);
rsec_status_t rsec_open_content(rsec_engine_t* engine,
    const uint8_t* id, size_t id_len, const uint8_t* envelope, size_t envelope_len,
    const rsec_sealed_content_t* content, uint8_t purpose,
    rsec_opened_content_t* out);
void rsec_commit_free(rsec_commit_t value);
void rsec_sealed_content_free(rsec_sealed_content_t value);
void rsec_opened_content_free(rsec_opened_content_t value);

typedef struct rsec_attachment_sealer rsec_attachment_sealer_t;
typedef struct rsec_attachment_opener rsec_attachment_opener_t;
rsec_status_t rsec_attachment_sealer_create(rsec_attachment_sealer_t** out);
rsec_status_t rsec_attachment_key(rsec_attachment_sealer_t* stream, rsec_buffer_t* out);
rsec_status_t rsec_attachment_seal(rsec_attachment_sealer_t* stream,
    const uint8_t* plaintext, size_t len, uint8_t last, rsec_buffer_t* out);
rsec_status_t rsec_attachment_digest(rsec_attachment_sealer_t* stream, rsec_buffer_t* out);
void rsec_attachment_sealer_destroy(rsec_attachment_sealer_t* stream);
rsec_status_t rsec_attachment_opener_create(const uint8_t* key, size_t key_len,
    const uint8_t* digest, size_t digest_len, rsec_attachment_opener_t** out);
rsec_status_t rsec_attachment_open(rsec_attachment_opener_t* stream,
    const uint8_t* chunk, size_t len, rsec_buffer_t* out);
rsec_status_t rsec_attachment_finish(rsec_attachment_opener_t* stream);
void rsec_attachment_opener_destroy(rsec_attachment_opener_t* stream);
rsec_status_t rsec_storage_key(rsec_buffer_t* out);
rsec_status_t rsec_storage_seal(const uint8_t* key, size_t key_len,
    const uint8_t* aad, size_t aad_len, const uint8_t* plaintext, size_t len,
    rsec_buffer_t* out);
rsec_status_t rsec_storage_open(const uint8_t* key, size_t key_len,
    const uint8_t* aad, size_t aad_len, const uint8_t* ciphertext, size_t len,
    rsec_buffer_t* out);

void rsec_buffer_free(rsec_buffer_t buffer);

#ifdef __cplusplus
}
#endif
