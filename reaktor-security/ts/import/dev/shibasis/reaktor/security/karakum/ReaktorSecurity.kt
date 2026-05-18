// Automatically generated - do not modify!

package dev.shibasis.reaktor.security.karakum

external interface ReaktorSecurity {
fun ensureDeviceRegistered(authContext: AuthContext): js.promise.Promise<DeviceCryptoIdentity>
fun publishKeyPackages(input: ReaktorSecurityPublishKeyPackagesInput = definedExternally): js.promise.Promise<KeyPackagePublishResult>
fun createDirectConversation(input: ReaktorSecurityCreateDirectConversationInput): js.promise.Promise<SecureConversation>
fun createGroupConversation(input: ReaktorSecurityCreateGroupConversationInput): js.promise.Promise<SecureConversation>
fun encryptMessage(input: ReaktorSecurityEncryptMessageInput): js.promise.Promise<SecureEnvelope>
fun processIncoming(envelope: SecureEnvelope): js.promise.Promise<IncomingSecurityResult>
fun addMembers(input: ReaktorSecurityAddMembersInput): js.promise.Promise<SecurityCommitResult>
fun removeMembers(input: ReaktorSecurityRemoveMembersInput): js.promise.Promise<SecurityCommitResult>
}
