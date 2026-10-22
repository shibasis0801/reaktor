package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface DatabaseMigrations {
    @Serializable
    data class Postgres(override val directory: String, val connections: Map<Environment, String>, val sources: List<String>) : DatabaseMigrations
    @Serializable
    data class D1(override val directory: String, val deployment: WorkerDeploySpec, val binding: String,
        val environments: List<Environment>) : DatabaseMigrations

    @Serializable
    data class ClickHouse(override val directory: String, val files: List<String>, val databases: Map<Environment, String>,
        val namespace: String, val pod: String, val service: ServiceRef) : DatabaseMigrations

    val directory: String
    val availableEnvironments: List<Environment> get() = when (this) {
        is Postgres -> connections.keys.toList()
        is D1 -> environments
        is ClickHouse -> databases.keys.toList()
    }
}

@Serializable
data class MigrationSpec(val node: NodeId, val environment: Environment, val database: DatabaseMigrations, val kubeconfig: String? = null) {
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
            is DatabaseMigrations.ClickHouse -> {
                require(!kubeconfig.isNullOrBlank() && environment in database.databases)
                require(database.databases.values.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) })
                require(database.files.isNotEmpty() && database.files.distinct() == database.files &&
                    database.files.all { it.matches(Regex("[a-z][a-z0-9_]*\\.sql")) })
                require(listOf(database.namespace, database.pod).all { it.matches(Regex("[a-z0-9][a-z0-9.-]{0,252}")) })
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
