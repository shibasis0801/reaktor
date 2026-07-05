package dev.shibasis.reaktor.auth.spring

import dev.shibasis.reaktor.auth.jwt.SigningKeys
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Publishes the public signing keys so any verifier — the Spring security filter, other Reaktor
 * services, and edge Workers — can validate Reaktor-issued ES256 access tokens LOCALLY, with no
 * shared secret and no per-request call back to the auth server.
 *
 * `JWKSet.toString()` emits the public-only JWK Set JSON (`{ "keys": [ … ] }`); the private `d`
 * is never included because [SigningKeys.publicJwkSet] is built from `toPublicJWK()`.
 *
 * This route MUST be on the public allowlist of the security filter. See the auth-analysis
 * "HTTP Surface" and "Spring Boundary" sections.
 */
@RestController
class JwksController(
    private val signingKeys: SigningKeys,
) {
    @GetMapping("/.well-known/jwks.json", produces = ["application/json"])
    fun jwks(): String = signingKeys.publicJwkSet().toString()
}
