// Automatically generated - do not modify!

package dev.shibasis.reaktor.security.karakum

external interface SecureEnvelope {
var magic: String /* "RSEC" */
var version: Double /* 1 */
var tenantId: String
var appId: String
var conversationId: String
var protocol: String /* "MLS" */
var mlsGroupId: RsecBytes
var mlsEpoch: Double?
var senderUserId: String
var senderDeviceId: String
var messageId: String
var contentKind: SecureEnvelopeContentKind
var aad: RsecBytes
var payload: RsecBytes
}
