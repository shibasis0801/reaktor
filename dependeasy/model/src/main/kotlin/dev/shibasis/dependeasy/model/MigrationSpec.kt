package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface DatabaseMigrations {
    @Serializable
    data class Postgres(override val directory: String, val connections: Map<Environment, String>, val sources: List<String>) : DatabaseMigrations
    @Serializable
    data class D1(override val directory: String, val deployment: WorkerDeploySpec, val binding: String,
        val environments: List<Environment>) : DatabaseMigrations

    val directory: String
    val availableEnvironments: List<Environment> get() = when (this) {
        is Postgres -> connections.keys.toList()
        is D1 -> environments
    }
}

@Serializable
data class MigrationSpec(val node: NodeId, val environment: Environment, val database: DatabaseMigrations) {
    init {
        require(database.directory.isNotBlank() && !java.io.File(database.directory).isAbsolute &&
            '\\' !in database.directory && database.directory.split('/').none { it == ".." || it.isEmpty() })
        when (database) {
            is DatabaseMigrations.Postgres -> {
                require(database.connections[environment]?.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) == true)
                require(database.sources.distinct().size == database.sources.size && database.sources.all {
                    it.startsWith("config/") && '\\' !in it && it.split('/').none { part -> part == ".." || part.isEmpty() }
                })
            }
            is DatabaseMigrations.D1 -> {
                require(database.binding.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
                require(database.environments.isNotEmpty() && database.environments.distinct() == database.environments)
                require(environment in database.environments && database.deployment.environment == environment && database.deployment.node == node)
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
    val pending: List<String>, val gitSha: String, val runner: String, val configurationDigest: String? = null)
