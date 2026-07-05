package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.auth.jwt.AuthenticatedUser
import dev.shibasis.reaktor.auth.jwt.JwtMinter
import dev.shibasis.reaktor.auth.jwt.JwtVerifier
import dev.shibasis.reaktor.auth.jwt.SigningKeys
import dev.shibasis.reaktor.auth.runtime.AUTH_RUNTIME_CONFIG_DEPENDENCY
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeConfig
import dev.shibasis.reaktor.auth.runtime.ports.AuthExternalIdentityVerifier
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.di.dependency
import dev.shibasis.reaktor.portgraph.port.provides

class AuthJwtNode(
    graph: Graph,
) : BasicNode(graph), AuthExternalIdentityVerifier {
    private val config by dependency<AuthRuntimeConfig>(AUTH_RUNTIME_CONFIG_DEPENDENCY)

    init {
        require(config.ecJwkJson.isNotBlank() || config.allowEphemeralSigningKey) {
            "reaktor.jwt.ec-jwk is not configured and ephemeral signing keys are disabled. " +
                "Supply an EC JWK (from Secret Manager) in production, or set " +
                "reaktor.auth.allow-ephemeral-signing-key=true for local/dev only."
        }
    }

    val signingKeys = SigningKeys(config.ecJwkJson, config.ecJwkPrevJson)
    val minter = JwtMinter(signingKeys)
    val verifier = JwtVerifier(config.userAuthenticationProviders, signingKeys)

    val signingKeysPort by provides<SigningKeys>(signingKeys)
    val jwtMinterPort by provides<JwtMinter>(minter)
    val jwtVerifierPort by provides<JwtVerifier>(verifier)
    val externalIdentityVerifierPort by provides<AuthExternalIdentityVerifier>(this)

    override suspend fun verify(request: LoginRequest): Result<AuthenticatedUser> =
        verifier(request)
}
