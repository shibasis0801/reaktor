// Automatically generated - do not modify!

package dev.shibasis.reaktor.security.karakum

external interface SecureConversation {
var conversationId: String
var mlsGroupId: RsecBytes
var epoch: Double
var memberUserIds: js.array.ReadonlyArray<String>
}
