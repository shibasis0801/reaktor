package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.App
import dev.shibasis.reaktor.auth.AuthPrincipal
import dev.shibasis.reaktor.auth.db.AppRepository
import dev.shibasis.reaktor.auth.db.AuthRepository
import dev.shibasis.reaktor.auth.db.ResolvedAnonymousPrincipal
import dev.shibasis.reaktor.auth.db.ResolvedAuthPrincipal
import dev.shibasis.reaktor.auth.kernel.AuthProviderKind
import dev.shibasis.reaktor.auth.runtime.AUTH_EXPOSED_ADAPTER_DEPENDENCY
import dev.shibasis.reaktor.auth.runtime.ports.AuthAppCatalog
import dev.shibasis.reaktor.auth.runtime.ports.AuthPrincipalDirectory
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.di.dependency
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.db.service.ExposedAdapter
import dev.shibasis.reaktor.portgraph.port.provides
import kotlinx.serialization.json.JsonElement
import java.util.UUID

class AuthRepositoryNode(
    graph: Graph,
) : BasicNode(graph), AuthAppCatalog, AuthPrincipalDirectory {
    private val adapter by dependency<ExposedAdapter>(AUTH_EXPOSED_ADAPTER_DEPENDENCY)
    private val appRepository = AppRepository(adapter)
    private val authRepository = AuthRepository(adapter)

    val appCatalogPort by provides<AuthAppCatalog>(this)
    val principalDirectoryPort by provides<AuthPrincipalDirectory>(this)

    override suspend fun findById(request: Request, id: UUID): Result<App?> =
        appRepository.findById(request, id)

    override suspend fun findByName(request: Request, name: String): Result<App?> =
        appRepository.findByName(request, name)

    override suspend fun all(request: Request): Result<List<App>> =
        appRepository.all(request)

    override suspend fun resolveAnonymousLogin(
        request: Request,
        app: App,
        profile: JsonElement,
        tenantId: String?,
        contextId: String?,
    ): Result<ResolvedAnonymousPrincipal> = authRepository.resolveAnonymousLogin(
        request = request,
        app = app,
        profile = profile,
        tenantId = tenantId,
        contextId = contextId,
    )

    override suspend fun resolveExternalLogin(
        request: Request,
        app: App,
        provider: AuthProviderKind,
        issuer: String,
        subject: String,
        email: String?,
        emailVerified: Boolean,
        profile: JsonElement,
        tenantId: String?,
        contextId: String?,
    ): Result<ResolvedAuthPrincipal> = authRepository.resolveExternalLogin(
        request = request,
        app = app,
        provider = provider,
        issuer = issuer,
        subject = subject,
        email = email,
        emailVerified = emailVerified,
        profile = profile,
        tenantId = tenantId,
        contextId = contextId,
    )

    override suspend fun getPrincipalPermissions(request: Request, principalId: UUID, appId: UUID): Result<List<String>> =
        authRepository.getPrincipalPermissions(request, principalId, appId)

    override suspend fun getPrincipalRoles(request: Request, principalId: UUID, appId: UUID): Result<List<String>> =
        authRepository.getPrincipalRoles(request, principalId, appId)

    override suspend fun getPrincipal(request: Request, principalId: UUID): Result<AuthPrincipal?> =
        authRepository.getPrincipal(request, principalId)

    override suspend fun softDeleteAccount(request: Request, principalId: UUID): Result<Boolean> =
        authRepository.softDeleteAccount(request, principalId)
}
