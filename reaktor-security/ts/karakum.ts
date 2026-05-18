export type SecureContentType = "text" | "attachmentMetadata" | "appEvent";
export type RsecBytes = Uint8Array<ArrayBuffer>;

export interface AuthContext {
  tenantId: string;
  appId: string;
  userId: string;
  authToken: string;
  authKeyId: string;
}

export interface DeviceCryptoIdentity {
  tenantId: string;
  appId: string;
  userId: string;
  deviceId: string;
  credentialBytes: RsecBytes;
  fingerprint: string;
}

export interface KeyPackagePublishResult {
  deviceId: string;
  publishedCount: number;
  availableCount: number;
}

export interface SecureConversation {
  conversationId: string;
  mlsGroupId: RsecBytes;
  epoch: number;
  memberUserIds: string[];
}

export interface SecureEnvelope {
  magic: "RSEC";
  version: 1;
  tenantId: string;
  appId: string;
  conversationId: string;
  protocol: "MLS";
  mlsGroupId: RsecBytes;
  mlsEpoch?: number;
  senderUserId: string;
  senderDeviceId: string;
  messageId: string;
  contentKind: "application" | "proposal" | "commit" | "welcome" | "keyPackage";
  aad: RsecBytes;
  payload: RsecBytes;
}

export interface SecurityCommitResult {
  conversationId: string;
  epoch: number;
  commitEnvelope: SecureEnvelope;
  welcomeEnvelopes: SecureEnvelope[];
}

export interface IncomingSecurityResult {
  type: "applicationMessage" | "handshakeProcessed" | "queued";
  conversationId: string;
  envelope?: SecureEnvelope;
  plaintext?: RsecBytes;
  reason?: string;
}

export interface ReaktorSecurity {
  ensureDeviceRegistered(authContext: AuthContext): Promise<DeviceCryptoIdentity>;

  publishKeyPackages(input?: {
    desiredAvailableCount?: number;
  }): Promise<KeyPackagePublishResult>;

  createDirectConversation(input: {
    peerUserId: string;
  }): Promise<SecureConversation>;

  createGroupConversation(input: {
    memberUserIds: string[];
  }): Promise<SecureConversation>;

  encryptMessage(input: {
    conversationId: string;
    plaintext: RsecBytes;
    contentType: SecureContentType;
    aad?: Record<string, string>;
  }): Promise<SecureEnvelope>;

  processIncoming(envelope: SecureEnvelope): Promise<IncomingSecurityResult>;

  addMembers(input: {
    conversationId: string;
    userIds: string[];
  }): Promise<SecurityCommitResult>;

  removeMembers(input: {
    conversationId: string;
    userIds: string[];
  }): Promise<SecurityCommitResult>;
}
