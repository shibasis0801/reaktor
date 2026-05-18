// Automatically generated - do not modify!

package dev.shibasis.reaktor.security.karakum

external interface ReaktorSecurityEncryptMessageInput {
var conversationId: String
var plaintext: RsecBytes
var contentType: SecureContentType
var aad: js.objects.ReadonlyRecord<String, String>?
}
