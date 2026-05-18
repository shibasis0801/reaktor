package dev.shibasis.reaktor.auth.graph

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.auth.AccountType
import dev.shibasis.reaktor.auth.User
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDefaults
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.auth.kernel.Scope
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.auth.db.AuthObjectStore
import dev.shibasis.reaktor.db.Database
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.provides
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A runtime representation of an active, authenticated context.
 * Useful for graph nodes that need to verify scopes dynamically.
 */
data class AuthSession(
    val user: User,
    val scopes: List<String>?,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val authContext: AuthContext? = null
) {
    fun toAuthContext(): AuthContext {
        authContext?.let { return it }
        val principalKind = when (user.accountType) {
            AccountType.SERVICE_ACCOUNT -> PrincipalKind.SERVICE
            AccountType.USER -> PrincipalKind.USER
        }
        val scopeSet = scopes.orEmpty().map(::Scope).toSet()
        return AuthContext(
            principal = PrincipalRef(user.id, principalKind),
            identityId = null,
            appId = user.appId,
            tenantId = null,
            contextId = null,
            issuer = AuthDefaults.ISSUER,
            audience = user.appId,
            scopes = scopeSet,
            permissions = scopeSet.map { PermissionRef(name = it.value) }.toSet(),
            method = AuthMethod.ACCESS_TOKEN,
        )
    }
}

/**
 * A headless node instantiated near the Root Graph responsible for providing Auth state to the rest of the application.
 */
open class AuthNode(graph: Graph): BasicNode(graph) {
    private val _authState = MutableStateFlow<AuthSession?>(null)
    val authState = _authState.asStateFlow()

    // Expose the auth state as a ProviderPort so other parts of the graph can consume it via dependency injection rules.
    val authPort by provides<MutableStateFlow<AuthSession?>>(_authState)

    init {
        // Attempt to rehydrate the user and tokens from ObjectDatabase on boot
        launch {
            val db = Feature.Database
            if (db != null) {
                try {
                    val authStore = AuthObjectStore(db)
                    val cachedUser = authStore.getUser()
                    val cachedAccessToken = authStore.getAccessToken()
                    val cachedRefreshToken = authStore.getRefreshToken()

                    if (cachedUser != null) {
                        // Assuming basic scopes to user mapping or relying on a fetched permissions list.
                        _authState.value = AuthSession(
                            user = cachedUser,
                            scopes = listOf("user"), // Default or fetched scopes
                            accessToken = cachedAccessToken,
                            refreshToken = cachedRefreshToken
                        )
                    }
                } catch (e: Exception) {
                    Logger.e(e) { "Failed to hydrate AuthSession from DB" }
                    // Clear state on failure to ensure UI prompts login
                    _authState.value = null
                }
            }
        }
    }

    // Typical operations
    fun login(session: AuthSession) {
        _authState.value = session
    }

    fun logout() {
        _authState.value = null
        // Trigger deletion from DB here
        launch {
            val db = Feature.Database
            if (db != null) {
                val authStore = AuthObjectStore(db)
                authStore.clear()
            }
        }
    }
}
