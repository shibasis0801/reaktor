package dev.shibasis.reaktor.auth.jwt

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Holds the asymmetric (ES256 / EC P-256) signing key(s) for Reaktor-issued access tokens and
 * publishes the public half as a JWK Set for LOCAL verification — by the Spring security filter
 * and, crucially, by edge Workers (no shared secret, no per-request DB/IdP hit).
 *
 * This replaces the previous HS256 (shared-secret) signing: with HS256 every verifier needed the
 * secret; with ES256 + JWKS verifiers only need the public key. See the auth-analysis
 * "Token Families" and "HTTP Surface" sections.
 *
 * Key source, in priority order:
 *   1. `reaktor.jwt.ec-jwk`       — a full EC JWK JSON (with the private `d`). The PRODUCTION path:
 *                                   load it from Secret Manager into this property. Stable across restarts
 *                                   and shared across instances.
 *   2. `reaktor.jwt.ec-jwk-prev`  — optional previous key, kept in the JWKS during rotation so tokens
 *                                   signed with the old key still verify until they expire.
 *   3. (neither set)              — generate an EPHEMERAL P-256 key at boot. DEV ONLY: tokens do not
 *                                   survive a restart and other instances cannot verify them.
 *
 * Rotation: this is config-driven by design — **private keys live in Secret Manager, never in the DB**
 * (the `signing_key` table holds only the public JWK + a `private_key_ref`). To rotate: promote the new
 * key into `reaktor.jwt.ec-jwk` and move the outgoing one to `reaktor.jwt.ec-jwk-prev`; both stay in the
 * published JWKS so in-flight tokens keep verifying until they expire. Because every instance reads the
 * same Secret-Manager-backed config, the JWKS is consistent fleet-wide with no DB hit on the verify path.
 * See the auth-analysis "Token Families" section.
 */
class SigningKeys(
    private val ecJwkJson: String,
    private val ecJwkPrevJson: String,
) {
    private val log = LoggerFactory.getLogger(SigningKeys::class.java)

    /** The signing algorithm for Reaktor-issued tokens. ES256 is the safe, edge-interoperable default. */
    val algorithm: JWSAlgorithm = JWSAlgorithm.ES256

    private val current: ECKey = if (ecJwkJson.isNotBlank()) {
        ECKey.parse(ecJwkJson)
    } else {
        log.warn(
            "reaktor.jwt.ec-jwk is not set — generating an EPHEMERAL ES256 signing key. " +
                "DEV ONLY: issued tokens will not survive a restart and cannot be verified by other instances. " +
                "Set reaktor.jwt.ec-jwk from Secret Manager in any real environment."
        )
        ECKeyGenerator(Curve.P_256)
            .keyID("rk-${UUID.randomUUID().toString().take(8)}")
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(algorithm)
            .generate()
    }

    private val previous: ECKey? =
        ecJwkPrevJson.takeIf { it.isNotBlank() }?.let { ECKey.parse(it) }

    /** `kid` of the current signing key — written into every token's JOSE header for verifier key selection. */
    val kid: String get() = current.keyID

    /** Signer bound to the current private key. */
    val signer: JWSSigner = ECDSASigner(current)

    /** Public JWK Set (current + previous), served at `/.well-known/jwks.json` and used for local verification. */
    fun publicJwkSet(): JWKSet =
        JWKSet(listOfNotNull<JWK>(current.toPublicJWK(), previous?.toPublicJWK()))
}
