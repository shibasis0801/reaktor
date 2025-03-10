package dev.shibasis.reaktor.auth.services

import kotlinx.serialization.json.JsonElement

interface ProfileService {
    suspend fun fetchProfile(userId: Int): JsonElement
    suspend fun createProfile(userId: Int, profile: JsonElement)
}