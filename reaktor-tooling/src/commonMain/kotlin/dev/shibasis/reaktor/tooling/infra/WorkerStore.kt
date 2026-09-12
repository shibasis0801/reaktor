package dev.shibasis.reaktor.tooling.infra

import dev.shibasis.reaktor.tooling.database.SqlReadStatement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WorkerStore(val provider: String, val binding: String)

@Serializable
data class WorkerStoreRead(val statement: String, val maxRows: Int = 100, val explain: Boolean = false, val catalog: WorkerStoreCatalog? = null) {
    fun validate(store: WorkerStore): WorkerStoreRead {
        require(catalog == null || (store.provider == "D1" && !explain)) { "Only D1 exposes this catalog broker" }
        require(maxRows in 1..500 && statement.encodeToByteArray().size in 1..262_144)
        return if (store.provider == "D1") copy(statement = SqlReadStatement.normalize(statement)) else {
            require(!explain) { "This store does not expose a query planner" }
            Json.decodeFromString<StoreKeyQuery>(statement).validate(store.provider)
            this
        }
    }
}

@Serializable
data class StoreKeyQuery(
    val key: String? = null,
    val prefix: String = "",
    val cursor: String? = null,
    val instance: String? = null,
) {
    fun validate(provider: String) {
        require(provider in setOf("Kv", "R2", "DurableObjects")) { "Unknown store provider" }
        require(listOfNotNull(key, prefix, cursor, instance).all { it.length <= 4096 && '\u0000' !in it })
        require(key == null || (prefix.isEmpty() && cursor == null)) { "Use a key or a prefix and cursor" }
        if (provider == "DurableObjects") {
            require(!instance.isNullOrBlank() && key == null && prefix.isEmpty() && cursor == null) {
                "Select a named instance to read its application state"
            }
        } else require(instance == null) { "Instance selection is only supported for Durable Objects" }
    }
}

@Serializable
enum class WorkerStoreCatalog { Schema, Statistics }
