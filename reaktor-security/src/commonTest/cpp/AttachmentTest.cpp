#include <gtest/gtest.h>
#include <rsec/Attachment.h>
#include <rsec/rsec.h>
#include <algorithm>

TEST(AttachmentTest, StreamsEmptyShortAndBoundarySizedFiles)
{
  for (size_t size : {size_t(0), size_t(17), size_t(65536), size_t(131079)}) {
    rsec::Bytes input(size, 0x63), output;
    rsec::AttachmentSealer sealer;
    std::vector<rsec::Bytes> chunks;
    for (size_t offset = 0;;) {
      const auto length = std::min(size - offset, size_t(65536));
      const auto last = offset + length == size;
      chunks.push_back(sealer.seal({input.begin() + offset, input.begin() + offset + length}, last));
      offset += length;
      if (last) break;
    }
    rsec::AttachmentOpener opener(sealer.key(), sealer.digest());
    for (const auto& chunk : chunks) {
      auto plain = opener.open(chunk);
      output.insert(output.end(), plain.begin(), plain.end());
    }
    EXPECT_NO_THROW(opener.finish());
    EXPECT_EQ(output, input);
    EXPECT_THROW(opener.open(chunks.back()), rsec::Error);
    EXPECT_THROW(sealer.seal({}, true), rsec::Error);
  }
}

TEST(AttachmentTest, RefusesTruncationReorderingTamperingAndWrongDigest)
{
  rsec::AttachmentSealer sealer;
  auto first = sealer.seal(rsec::Bytes(65536, 0x64), false);
  EXPECT_THROW(sealer.digest(), rsec::Error);
  auto last = sealer.seal({1, 2, 3}, true);
  auto key = sealer.key();
  auto digest = sealer.digest();
  rsec::AttachmentOpener opener(key, digest);
  EXPECT_THROW(opener.open(last), rsec::Error);
  opener.open(first);
  EXPECT_THROW(opener.finish(), rsec::Error);
  EXPECT_THROW(opener.open(first), rsec::Error);
  auto changed = last;
  changed[4] = 0;
  EXPECT_THROW(opener.open(changed), rsec::Error);
  changed = last;
  changed.back() ^= 1;
  EXPECT_THROW(opener.open(changed), rsec::Error);
  changed = last;
  changed.pop_back();
  EXPECT_THROW(opener.open(changed), rsec::Error);
  opener.open(last);
  EXPECT_NO_THROW(opener.finish());
  digest.front() ^= 1;
  rsec::AttachmentOpener substituted(key, digest);
  substituted.open(first);
  substituted.open(last);
  EXPECT_THROW(substituted.finish(), rsec::Error);
}


TEST(AttachmentTest, PackedFilesRoundTripAndUseFreshKeys)
{
  for (size_t size : {size_t(0), size_t(1), size_t(65535), size_t(65536), size_t(65537), size_t(262147)}) {
    rsec::Bytes input(size, 0x63);
    const auto sealed = rsec::seal_attachment(input);
    const auto another = rsec::seal_attachment(input);
    EXPECT_EQ(sealed.key.size(), 32);
    EXPECT_EQ(sealed.digest.size(), 32);
    EXPECT_NE(sealed.key, another.key);
    EXPECT_NE(sealed.ciphertext, another.ciphertext);
    EXPECT_EQ(rsec::open_attachment(sealed.key, sealed.digest, sealed.ciphertext), input);
  }
}

TEST(AttachmentTest, PackedFilesRejectTamperingTruncationReorderingAndAppendedChunks)
{
  const auto sealed = rsec::seal_attachment(rsec::Bytes(131079, 0x64));
  for (size_t length : {size_t(0), size_t(16), size_t(65569), sealed.ciphertext.size() - 1}) {
    const rsec::Bytes truncated(sealed.ciphertext.begin(), sealed.ciphertext.begin() + length);
    EXPECT_THROW(rsec::open_attachment(sealed.key, sealed.digest, truncated), rsec::Error);
  }
  auto changed = sealed.ciphertext;
  changed.back() ^= 1;
  EXPECT_THROW(rsec::open_attachment(sealed.key, sealed.digest, changed), rsec::Error);
  changed = sealed.ciphertext;
  changed.insert(changed.end(), sealed.ciphertext.begin(), sealed.ciphertext.begin() + 65569);
  EXPECT_THROW(rsec::open_attachment(sealed.key, sealed.digest, changed), rsec::Error);
  changed = sealed.ciphertext;
  std::rotate(changed.begin(), changed.begin() + 65569, changed.begin() + 131138);
  EXPECT_THROW(rsec::open_attachment(sealed.key, sealed.digest, changed), rsec::Error);
  auto digest = sealed.digest;
  digest.front() ^= 1;
  EXPECT_THROW(rsec::open_attachment(sealed.key, digest, sealed.ciphertext), rsec::Error);
  auto key = sealed.key;
  key.front() ^= 1;
  EXPECT_THROW(rsec::open_attachment(key, sealed.digest, sealed.ciphertext), rsec::Error);
}

TEST(AttachmentTest, PackedFileCAbiWithholdsPlaintextOnFailure)
{
  rsec::Bytes input(65543, 0x65);
  rsec_attachment_t sealed{};
  ASSERT_EQ(rsec_attachment_seal_file(input.data(), input.size(), &sealed), RSEC_OK);
  rsec_buffer_t opened{};
  ASSERT_EQ(rsec_attachment_open_file(sealed.key.data, sealed.key.len, sealed.digest.data, sealed.digest.len,
      sealed.ciphertext.data, sealed.ciphertext.len, &opened), RSEC_OK);
  EXPECT_EQ((rsec::Bytes{opened.data, opened.data + opened.len}), input);
  rsec_buffer_free(opened);
  sealed.digest.data[0] ^= 1;
  EXPECT_EQ(rsec_attachment_open_file(sealed.key.data, sealed.key.len, sealed.digest.data, sealed.digest.len,
      sealed.ciphertext.data, sealed.ciphertext.len, &opened), RSEC_ERR_CRYPTO);
  EXPECT_EQ(opened.data, nullptr);
  EXPECT_EQ(opened.len, 0);
  sealed.digest.data[0] ^= 1;
  EXPECT_EQ(rsec_attachment_open_file(sealed.key.data, sealed.key.len - 1, sealed.digest.data, sealed.digest.len,
      sealed.ciphertext.data, sealed.ciphertext.len, &opened), RSEC_ERR_INVALID_ARGUMENT);
  EXPECT_EQ(opened.data, nullptr);
  rsec_attachment_free(sealed);
}

TEST(AttachmentTest, StorageRecordsBindTheKeyAndRecordIdentity)
{
  const auto key = rsec::storage_key();
  const rsec::Bytes aad{1, 2}, body{3, 4, 5};
  const auto sealed = rsec::storage_seal(key, aad, body);
  EXPECT_EQ(rsec::storage_open(key, aad, sealed), body);
  EXPECT_NE(rsec::storage_seal(key, aad, body), sealed);
  EXPECT_THROW(rsec::storage_open(key, {2, 1}, sealed), rsec::Error);
  EXPECT_THROW(rsec::storage_open(rsec::storage_key(), aad, sealed), rsec::Error);
  auto changed = sealed;
  changed.back() ^= 1;
  EXPECT_THROW(rsec::storage_open(key, aad, changed), rsec::Error);
  changed = sealed;
  changed.pop_back();
  EXPECT_THROW(rsec::storage_open(key, aad, changed), rsec::Error);
}
