package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Real RS256 tokens, signed here with Web Crypto by a key the verifier is pinned to — the same
 * shape as a Google or Apple ID token, without a network.
 */
class IdTokenVerifierTest {
    private val issuer = "https://accounts.google.com"
    private val audience = "hearth-client.apps.googleusercontent.com"

    private class Keys(val private: dynamic, val jwks: String)

    private suspend fun keys(kid: String = "k1"): Keys {
        val pair = generateRsa().await()
        val jwk = exportJwk(pair.publicKey).await()
        jwk.kid = kid
        jwk.alg = "RS256"
        jwk.use = "sig"
        return Keys(pair.privateKey, stringify(js("({keys: [jwk]})")))
    }

    private suspend fun token(
        keys: Keys,
        alg: String = "RS256",
        kid: String = "k1",
        iss: String = issuer,
        aud: String = audience,
        sub: String = "1234567890",
        expiresIn: Int = 3600,
    ): String {
        val now = (js("Date.now()") as Double / 1000).toLong()
        val header = """{"alg":"$alg","kid":"$kid","typ":"JWT"}"""
        val payload = """{"iss":"$iss","aud":"$aud","sub":"$sub","email":"a@example.com","iat":$now,"exp":${now + expiresIn}}"""
        val input = base64Url(header) + "." + base64Url(payload)
        return input + "." + sign(keys.private, input).await()
    }

    private fun verifier(keys: Keys) = IdTokenVerifier(
        listOf(IdentityProvider("google", setOf(issuer), "https://unused.invalid", setOf(audience), jwksJson = keys.jwks)),
    )

    @Test
    fun aGenuineTokenNamesTheProviderAndTheSubject() = runTest {
        val keys = keys()
        val identity = verifier(keys).verify(token(keys))
        assertNotNull(identity)
        assertEquals("google", identity.provider)
        assertEquals("1234567890", identity.subject)
        assertEquals("a@example.com", identity.email)
    }

    @Test
    fun aTokenForAnotherAppIsRefused() = runTest {
        val keys = keys()
        assertNull(verifier(keys).verify(token(keys, aud = "someone-elses-app")))
    }

    @Test
    fun anExpiredTokenIsRefused() = runTest {
        val keys = keys()
        assertNull(verifier(keys).verify(token(keys, expiresIn = -10)))
    }

    @Test
    fun aTokenWhosePayloadWasChangedIsRefused() = runTest {
        val keys = keys()
        val parts = token(keys).split(".")
        val forged = base64Url("""{"iss":"$issuer","aud":"$audience","sub":"someone-else","exp":99999999999}""")
        assertNull(verifier(keys).verify(parts[0] + "." + forged + "." + parts[2]))
    }

    @Test
    fun aTokenSignedByAnyOtherKeyIsRefused() = runTest {
        val trusted = keys()
        val other = keys()
        assertNull(verifier(trusted).verify(token(other)))
    }

    @Test
    fun theHeaderDoesNotChooseTheAlgorithm() = runTest {
        val keys = keys()
        assertNull(verifier(keys).verify(token(keys, alg = "none")))
        assertNull(verifier(keys).verify(token(keys, alg = "HS256")))
    }

    @Test
    fun anIssuerThatIsNotConfiguredIsRefused() = runTest {
        val keys = keys()
        assertNull(verifier(keys).verify(token(keys, iss = "https://evil.example")))
    }
}

private fun generateRsa(): Promise<dynamic> =
    js("crypto.subtle.generateKey({name:'RSASSA-PKCS1-v1_5',modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:'SHA-256'},true,['sign','verify'])")
        .unsafeCast<Promise<dynamic>>()

private fun exportJwk(key: dynamic): Promise<dynamic> =
    js("crypto.subtle.exportKey('jwk', key)").unsafeCast<Promise<dynamic>>()

private fun stringify(value: dynamic): String = js("JSON.stringify(value)").unsafeCast<String>()

private fun base64Url(text: String): String =
    js("Buffer.from(text, 'utf8').toString('base64').replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=+$/,'')").unsafeCast<String>()

private fun sign(key: dynamic, input: String): Promise<String> =
    js("crypto.subtle.sign({name:'RSASSA-PKCS1-v1_5'}, key, new TextEncoder().encode(input)).then(function(s){return Buffer.from(new Uint8Array(s)).toString('base64').replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=+$/,'');})")
        .unsafeCast<Promise<String>>()
