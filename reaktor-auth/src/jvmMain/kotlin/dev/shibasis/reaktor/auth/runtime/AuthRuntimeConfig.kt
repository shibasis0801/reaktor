package dev.shibasis.reaktor.auth.runtime

import dev.shibasis.reaktor.auth.jwt.UserAuthenticationProvider

data class AuthRuntimeConfig(
    val userAuthenticationProviders: List<UserAuthenticationProvider> = emptyList(),
    val ecJwkJson: String = "",
    val ecJwkPrevJson: String = "",
    val anonymousRoles: List<String> = listOf("guest"),
    val anonymousScopes: List<String> = emptyList(),
    val anonymousPermissions: List<String> = emptyList(),
    // When no signing key is configured (ecJwkJson blank), allow generating an ephemeral in-memory
    // ES256 key. DEV ONLY: ephemeral tokens don't survive a restart and can't be verified by other
    // instances or the edge. The permissive default keeps embedded/test construction working; the
    // production Spring path (AuthSpringConfiguration) flips this to false so a missing key fails
    // fast instead of silently issuing unverifiable tokens.
    val allowEphemeralSigningKey: Boolean = true,
    // Optional bootstrap bearer permitted to mint the first PAT before any auth:pat:mint grant
    // exists. Read from the deploy environment at the composition root — never via getenv inside a node.
    val patBootstrapToken: String? = null,
)
