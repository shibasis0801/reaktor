#pragma once

#include <rsec/MlsEngine.h>

namespace rsec {

class AttachmentSealer
{
public:
  AttachmentSealer();
  ~AttachmentSealer();
  AttachmentSealer(const AttachmentSealer&) = delete;
  AttachmentSealer& operator=(const AttachmentSealer&) = delete;
  Bytes key() const;
  Bytes seal(const Bytes& plaintext, bool last);
  Bytes digest() const;
private:
  struct Impl;
  Impl* impl_;
};

class AttachmentOpener
{
public:
  AttachmentOpener(const Bytes& key, const Bytes& digest);
  ~AttachmentOpener();
  AttachmentOpener(const AttachmentOpener&) = delete;
  AttachmentOpener& operator=(const AttachmentOpener&) = delete;
  Bytes open(const Bytes& chunk);
  void finish() const;
private:
  struct Impl;
  Impl* impl_;
};


struct SealedAttachment
{
  Bytes key;
  Bytes digest;
  Bytes ciphertext;
};

SealedAttachment seal_attachment(const Bytes& plaintext);
Bytes open_attachment(const Bytes& key, const Bytes& digest, const Bytes& ciphertext);

Bytes storage_key();
Bytes storage_seal(const Bytes& key, const Bytes& aad, const Bytes& plaintext);
Bytes storage_open(const Bytes& key, const Bytes& aad, const Bytes& ciphertext);

}
