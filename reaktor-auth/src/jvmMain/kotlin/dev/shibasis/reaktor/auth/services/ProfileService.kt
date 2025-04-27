package dev.shibasis.reaktor.auth.services

import kotlinx.serialization.json.JsonElement
import java.util.UUID

interface ProfileService {
    suspend fun fetchProfile(userId: UUID): JsonElement
    suspend fun createProfile(userId: UUID, profile: JsonElement)
}