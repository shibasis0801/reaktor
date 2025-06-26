package dev.shibasis.reaktor.auth.services

import dev.shibasis.reaktor.auth.Auditable
import dev.shibasis.reaktor.auth.UserStatus
import dev.shibasis.reaktor.auth.db.invoke
import kotlinx.serialization.json.JsonElement
import org.springframework.data.repository.NoRepositoryBean
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID


interface BaseProfileEntity: Auditable {
    val id: UUID
    fun toJson(): JsonElement
}


abstract class BaseProfileInteractor<T: BaseProfileEntity>(
    val repository: BaseProfileRepository<T>
)  {
    abstract suspend fun create(userId: UUID, profileData: JsonElement): Result<T>
    suspend fun fetch(userId: UUID) = repository { findById(userId) }
}


@NoRepositoryBean
interface BaseProfileRepository<T: BaseProfileEntity>: CoroutineCrudRepository<T, UUID>

