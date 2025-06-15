package dev.shibasis.reaktor.auth.db

import dev.shibasis.reaktor.auth.*
import org.springframework.context.annotation.Configuration
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

interface AppRepository: CoroutineCrudRepository<AppEntity, UUID> {
    suspend fun findByName(name: String): AppEntity?
}

interface UserRepository : CoroutineCrudRepository<UserEntity, UUID> {
    suspend fun findByName(name: String): UserEntity?
    suspend fun findByAppId(appId: UUID): UserEntity?
    suspend fun findBySocialIdAndAppId(socialId: String, appId: UUID): UserEntity?
}

interface ContextRepository : CoroutineCrudRepository<ContextEntity, UUID> {
    suspend fun findByAppId(appId: UUID): ContextEntity?
}

interface RoleRepository : CoroutineCrudRepository<RoleEntity, UUID> {
    suspend fun findByAppId(appId: UUID): RoleEntity?
}

interface PermissionRepository: CoroutineCrudRepository<PermissionEntity, UUID> {
    suspend fun findByAppId(appId: UUID): PermissionEntity?
}

interface RolePermissionRepository: CoroutineCrudRepository<RolePermissionEntity, UUID> {
    suspend fun findByRoleId(roleId: UUID): RolePermissionEntity?
    suspend fun findByPermissionId(permissionId: UUID): RolePermissionEntity?
}

interface UserRoleRepository: CoroutineCrudRepository<UserRoleEntity, UUID> {
    suspend fun findByUserId(userId: UUID): UserRoleEntity?
    suspend fun findByRoleId(roleId: UUID): UserRoleEntity?
    suspend fun findByContextId(contextId: UUID): UserRoleEntity?
    suspend fun findByUserIdAndContextId(userId: UUID, contextId: UUID): UserRoleEntity?
}

interface SessionRepository: CoroutineCrudRepository<SessionEntity, UUID> {
    suspend fun findByUserId(userId: UUID): SessionEntity?
    suspend fun findByUserIdAndAppIdAndContextId(userId: UUID, appId: UUID, contextId: UUID): SessionEntity?
}

suspend inline operator fun <Repo, R : Any> Repo.invoke(
    crossinline block: suspend Repo.() -> R?
): Result<R>
where Repo : CoroutineCrudRepository<*, *> =
    runCatching { block() ?: throw NullPointerException() }


@Configuration
@EnableR2dbcRepositories(
    basePackages = ["dev.shibasis.reaktor.auth.db"]
)
open class R2dbcRepoConfig
