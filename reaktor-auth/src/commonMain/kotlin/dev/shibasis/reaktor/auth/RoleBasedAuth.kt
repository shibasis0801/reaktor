package dev.shibasis.reaktor.auth

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement


@Serializable
data class App(
    val id: Long,
    val name: String,
    val data: JsonElement,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

@Serializable
data class Entity(
    val id: Long,
    val name: String,
    val data: JsonElement,
    val appId: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

@Serializable
data class User(
    val id: Long,
    val name: String,
    val socialId: String,
    val appId: Long,
    val data: JsonElement,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)


@Serializable
data class Role(
    val id: Long,
    val name: String,
    val appId: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

@Serializable
data class Permission(
    val id: Long,
    val name: String,
    val appId: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

