package dev.shibasis.reaktor.auth.services

import dev.shibasis.reaktor.auth.AuditableDto
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.framework.CrudRepository
import dev.shibasis.reaktor.framework.ExposedAdapter
import kotlinx.serialization.json.JsonElement


interface BaseProfile: AuditableDto {
    val id: String
    fun toJson(): JsonElement
}

abstract class BaseProfileRepository<Profile: BaseProfile>(adapter: ExposedAdapter): CrudRepository(adapter) {
    abstract suspend fun create(
        request: LoginRequest,
        userId: String
    ): Result<Profile>

    abstract suspend fun fetch(
        request: LoginRequest,
        userId: String
    ): Result<Profile>
}
