package dev.shibasis.reaktor.auth.db

import dev.shibasis.reaktor.auth.*
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.dialect.PostgresDialect
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

interface AppRepository: CoroutineCrudRepository<AppEntity, UUID> {
    suspend fun findByName(name: String): AppEntity?
}

interface UserRepository : CoroutineCrudRepository<UserEntity, UUID> {
    suspend fun findByName(name: String): UserEntity?
    suspend fun findByAppId(appId: UUID): UserEntity?
    suspend fun findBySocialIdAndAppId(socialId: String, appId: UUID, provider: UserProvider): UserEntity?
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

@Component
@ReadingConverter
class OffsetDateTimeToInstantConverter : Converter<OffsetDateTime, Instant> {
    override fun convert(source: OffsetDateTime): Instant {
        return source.toInstant()
    }
}

@Component
@WritingConverter
class InstantToOffsetDateTimeConverter : Converter<Instant, OffsetDateTime> {
    override fun convert(source: Instant): OffsetDateTime {
        return source.atOffset(ZoneOffset.UTC)
    }
}

@Configuration
@EnableR2dbcRepositories
open class R2dbcRepoConfig

@Configuration
open class DbConfig {
    @Bean
    open fun r2dbcCustomConversions(): R2dbcCustomConversions =
        R2dbcCustomConversions.of(
            PostgresDialect.INSTANCE,
            listOf(
                OffsetDateTimeToInstantConverter(), InstantToOffsetDateTimeConverter(),
            )
        )
}
