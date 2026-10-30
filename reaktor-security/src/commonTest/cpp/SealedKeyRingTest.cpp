#include <gtest/gtest.h>
#include <rsec/MlsEngine.h>
#include <rsec/rsec.h>
#include <memory>

TEST(SealedKeyRingTest, OpensAcceptedHistoryWithoutAnEngineAcrossEverySupportedSuite)
{
  for (const auto suite : {rsec::MlsCipherSuite::X25519_AES128GCM_SHA256_Ed25519,
      rsec::MlsCipherSuite::P256_AES128GCM_SHA256_P256,
      rsec::MlsCipherSuite::X25519_CHACHA20POLY1305_SHA256_Ed25519}) {
    rsec::Bytes ring;
    rsec::SealedContent old, future;
    {
      rsec::MlsEngine alice(rsec::Bytes{1}, suite), bob(rsec::Bytes{2}, suite);
      auto created = alice.prepare_create("chat", {bob.generate_key_packages(1).front().key_package});
      alice.accept_pending("chat", created.commit);
      bob.join_from_welcome("chat", created.welcome);
      old = alice.seal("chat", {1}, {2});
      ring = bob.export_key_ring();
      auto reader = rsec::SealedKeyRing::restore(ring);
      const auto before = bob.export_snapshot();
      EXPECT_TRUE(reader.open("chat", {1}, old).plaintext == rsec::Bytes{2});
      EXPECT_FALSE(reader.open("chat", {1}, old).duplicate);
      EXPECT_TRUE(bob.export_snapshot() == before);
      auto next = alice.prepare_rotate("chat");
      alice.accept_pending("chat", next.commit);
      bob.process_handshake("chat", next.commit);
      future = alice.seal("chat", {1}, {3});
      EXPECT_THROW(reader.open("chat", {1}, future), rsec::Error);
      ring = bob.export_key_ring();
    }
    auto reader = rsec::SealedKeyRing::restore(ring);
    EXPECT_TRUE(reader.open("chat", {1}, old).authenticated);
    EXPECT_TRUE(reader.open("chat", {1}, future).plaintext == rsec::Bytes{3});
    EXPECT_THROW(reader.open("other", {1}, old), rsec::Error);
    EXPECT_THROW(reader.open("chat", {2}, old), std::exception);
    auto bad = ring;
    bad.pop_back();
    EXPECT_THROW(rsec::SealedKeyRing::restore(bad), std::exception);
    bad = ring;
    bad.push_back(0);
    EXPECT_THROW(rsec::SealedKeyRing::restore(bad), std::exception);
    EXPECT_THROW(rsec::MlsEngine::restore(ring), std::exception);
  }
}

TEST(SealedKeyRingTest, CAbiReaderOutlivesTheEngineAndOwnsItsOutputs)
{
  const uint8_t credential[] = {1}, id[] = {2}, body[] = {3};
  rsec_engine_config_t config{credential, sizeof(credential), 3};
  rsec_engine_t* raw = nullptr;
  ASSERT_EQ(rsec_engine_create(&config, &raw), RSEC_OK);
  std::unique_ptr<rsec_engine_t, decltype(&rsec_engine_destroy)> engine(raw, rsec_engine_destroy);
  rsec_commit_t created{};
  ASSERT_EQ(rsec_prepare_create(engine.get(), id, sizeof(id), nullptr, 0, &created), RSEC_OK);
  uint64_t epoch = 0;
  ASSERT_EQ(rsec_accept_pending(engine.get(), id, sizeof(id), created.commit.data, created.commit.len, &epoch), RSEC_OK);
  rsec_commit_free(created);
  rsec_sealed_content_t sealed{};
  ASSERT_EQ(rsec_seal_content(engine.get(), id, sizeof(id), nullptr, 0, body, sizeof(body), 0, 0, &sealed), RSEC_OK);
  rsec_buffer_t encoded{};
  ASSERT_EQ(rsec_engine_key_ring(engine.get(), &encoded), RSEC_OK);
  rsec_key_ring_t* key_ring = nullptr;
  ASSERT_EQ(rsec_key_ring_create(nullptr, encoded.data, encoded.len, &key_ring), RSEC_OK);
  rsec_buffer_free(encoded);
  engine.reset();
  rsec_opened_content_t opened{};
  ASSERT_EQ(rsec_key_ring_open(key_ring, id, sizeof(id), nullptr, 0, &sealed, 0, &opened), RSEC_OK);
  EXPECT_TRUE(opened.plaintext.len == 1 && opened.plaintext.data[0] == body[0]);
  EXPECT_EQ(opened.authenticated, 1);
  EXPECT_EQ(opened.duplicate, 0);
  rsec_opened_content_free(opened);
  EXPECT_EQ(rsec_key_ring_open(nullptr, id, sizeof(id), nullptr, 0, &sealed, 0, &opened), RSEC_ERR_INVALID_ARGUMENT);
  EXPECT_EQ(opened.plaintext.data, nullptr);
  rsec_sealed_content_free(sealed);
  rsec_key_ring_destroy(key_ring);
  key_ring = reinterpret_cast<rsec_key_ring_t*>(1);
  EXPECT_EQ(rsec_key_ring_create(nullptr, nullptr, 1, &key_ring), RSEC_ERR_INVALID_ARGUMENT);
  EXPECT_EQ(key_ring, nullptr);
  rsec_key_ring_destroy(nullptr);
}
