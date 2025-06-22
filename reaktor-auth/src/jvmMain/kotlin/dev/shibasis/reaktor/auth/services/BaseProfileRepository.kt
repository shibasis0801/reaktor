package dev.shibasis.reaktor.auth.services

import dev.shibasis.reaktor.auth.Auditable
import dev.shibasis.reaktor.auth.UserStatus
import kotlinx.serialization.json.JsonElement
import org.springframework.data.repository.NoRepositoryBean
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID


interface BaseProfileEntity: Auditable {
    val id: UUID
}

@NoRepositoryBean
interface BaseProfileRepository<T: BaseProfileEntity>: CoroutineCrudRepository<T, UUID> {
    suspend fun create(userId: UUID, profileData: JsonElement): JsonElement = TODO("Consumers must implement save")
    suspend fun fetch(userId: UUID): JsonElement = TODO("Consumers must implement fetch")
    suspend fun getStatus(profile: JsonElement): UserStatus = TODO("Consumers must implement getStatus")
}

