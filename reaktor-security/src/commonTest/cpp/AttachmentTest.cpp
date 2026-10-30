#include <gtest/gtest.h>
#include <rsec/Attachment.h>

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
