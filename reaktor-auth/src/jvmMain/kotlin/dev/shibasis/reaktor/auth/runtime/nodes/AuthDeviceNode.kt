package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.api.DeviceCredentialRequest
import dev.shibasis.reaktor.auth.api.DeviceCredentialResponse
import dev.shibasis.reaktor.auth.jwt.JwtVerifier
import dev.shibasis.reaktor.auth.jwt.SigningKeys
import dev.shibasis.reaktor.auth.jwt.toAuthContext
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalStatus
import dev.shibasis.reaktor.auth.runtime.AUTH_RUNTIME_CONFIG_DEPENDENCY
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeConfig
import dev.shibasis.reaktor.auth.runtime.bearerToken
import dev.shibasis.reaktor.auth.runtime.ports.AuthDevices
import dev.shibasis.reaktor.auth.runtime.ports.AuthPrincipalDirectory
import dev.shibasis.reaktor.auth.runtime.ports.AuthSessionLifecycle
import dev.shibasis.reaktor.auth.services.DeviceCredentialIssuer
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.di.dependency
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides
import java.util.UUID

class AuthDeviceNode(graph: Graph) : BasicNode(graph), AuthDevices {
    private val config by dependency<AuthRuntimeConfig>(AUTH_RUNTIME_CONFIG_DEPENDENCY)
    val jwtVerifierPort by consumes<JwtVerifier>()
    val principalDirectoryPort by consumes<AuthPrincipalDirectory>()
    val sessionLifecyclePort by consumes<AuthSessionLifecycle>()
    val signingKeysPort by consumes<SigningKeys>()
    val devicesPort by provides<AuthDevices>(this)
    val issuer by lazy {
        DeviceCredentialIssuer(config.deviceCredentialJwkJson, config.deviceCredentialNextJwkJson,
            signingKeysPort { publicJwkSet() })
    }

    override suspend fun issue(request: DeviceCredentialRequest): DeviceCredentialResponse {
        val token = request.bearerToken() ?: return DeviceCredentialResponse(statusCode = StatusCode.UNAUTHORIZED)
        val claims = jwtVerifierPort { verifyReaktorToken(token, listOf(request.appId)) }.getOrNull()
            ?: return DeviceCredentialResponse(statusCode = StatusCode.UNAUTHORIZED)
        val caller = claims.toAuthContext()
        val sessionId = caller.sessionId ?: return DeviceCredentialResponse(statusCode = StatusCode.UNAUTHORIZED)
        val session = sessionLifecyclePort { activeSession(sessionId, request.environment) }
            ?: return DeviceCredentialResponse(statusCode = StatusCode.UNAUTHORIZED)
        if (caller.principal.kind != PrincipalKind.USER || session.principalId != caller.principal.id ||
            session.appId != request.appId || caller.appId != request.appId || session.tenantId != caller.tenantId) {
            return DeviceCredentialResponse(statusCode = StatusCode.FORBIDDEN)
        }
        val principal = principalDirectoryPort.suspended {
            getPrincipal(request, UUID.fromString(session.principalId))
        }.getOrNull()
        if (principal?.status != PrincipalStatus.ACTIVE || principal.kind != PrincipalKind.USER || principal.identityId == null) {
            return DeviceCredentialResponse(statusCode = StatusCode.FORBIDDEN)
        }
        val tenant = session.tenantId ?: session.appId
        if (!DeviceCredentialIssuer.verifyProof(tenant, session.principalId, request)) {
            return DeviceCredentialResponse(statusCode = StatusCode.BAD_REQUEST)
        }
        if (!issuer.available) return DeviceCredentialResponse(statusCode = StatusCode.SERVICE_UNAVAILABLE)
        return DeviceCredentialResponse(issuer.issue(tenant, session.principalId, request))
    }
}
