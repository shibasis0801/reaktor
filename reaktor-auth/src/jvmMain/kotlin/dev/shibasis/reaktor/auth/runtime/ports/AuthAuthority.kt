package dev.shibasis.reaktor.auth.runtime.ports

import dev.shibasis.reaktor.auth.AuthPrincipal
import dev.shibasis.reaktor.auth.Session
import dev.shibasis.reaktor.auth.api.*
import dev.shibasis.reaktor.auth.kernel.AuthorityGrant
import dev.shibasis.reaktor.service.Request
import java.util.UUID

data class ResolvedAuthorityGrant(val grant: AuthorityGrant, val permissions: List<String>, val principalId: String)
data class ActiveAuthoritySession(val principal: AuthPrincipal, val session: Session, val grants: List<ResolvedAuthorityGrant>)

interface AuthAuthorityDirectory {
    suspend fun snapshot(request: Request, principalId: UUID, sessionId: UUID, sourceAppId: UUID): Result<ActiveAuthoritySession?>
}

interface AuthAuthority {
    suspend fun grants(request: AuthorityGrantsRequest): AuthorityGrantsResponse
    suspend fun resolve(request: AuthorityResolveRequest): AuthorityResolveResponse
}
