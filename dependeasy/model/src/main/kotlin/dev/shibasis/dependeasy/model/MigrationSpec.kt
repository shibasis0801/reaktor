package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface DatabaseMigrations {
    @Serializable
    data class Postgres(val directory: String, val connections: Map<Environment, String>, val sources: List<String>) : DatabaseMigrations
}

@Serializable
data class MigrationSpec(val node: NodeId, val environment: Environment, val database: DatabaseMigrations) {
    init {
        when (database) {
            is DatabaseMigrations.Postgres -> {
                require(database.connections[environment]?.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) == true)
                require(database.directory.isNotBlank() && !java.io.File(database.directory).isAbsolute &&
                    database.directory.split('/').none { it == ".." || it.isEmpty() })
                require(database.sources.distinct().size == database.sources.size && database.sources.all {
                    it.startsWith("config/") && '\\' !in it && it.split('/').none { part -> part == ".." || part.isEmpty() }
                })
            }
        }
    }
}

@Serializable
data class MigrationFile(val path: String, val version: String, val name: String, val checksum: String)

@Serializable
data class AppliedMigration(val version: String, val name: String, val checksum: String)

@Serializable
data class MigrationPlan(val spec: MigrationSpec, val files: List<MigrationFile>, val applied: List<AppliedMigration>,
    val pending: List<String>, val gitSha: String, val runner: String)
