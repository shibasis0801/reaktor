// Automatically generated - do not modify!

package dev.shibasis.reaktor.security.karakum

external interface IncomingSecurityResult {
var type: IncomingSecurityResultType
var conversationId: String
var envelope: SecureEnvelope?
var plaintext: RsecBytes?
var reason: String?
}
