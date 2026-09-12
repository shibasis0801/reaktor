package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.Serializable

/** Executable provider identities and private payload references; never credentials. */
@Serializable
sealed interface InfrastructureOperation {
    @Serializable
    data class KubernetesRead(
        val kubeconfig: String,
        val namespace: String,
        val action: String,
        val resourceName: String = "",
    ) : InfrastructureOperation

    @Serializable
    data class DatabaseRead(
        val engine: DatabaseProvider,
        val connection: DatabaseConnection,
        val queryFile: String? = null,
        val resultFile: String? = null,
        val maxRows: Int = 100,
        val explain: Boolean = false,
        val resultFormat: DatabaseResultFormat = DatabaseResultFormat.Csv,
        val analyze: Boolean = false,
    ) : InfrastructureOperation

    @Serializable
    data class WorkerCall(
        val endpoint: String,
        val operation: String,
        val tokenEndpoint: String,
        val audience: String,
        val authEnvironment: String,
        val credentialFile: String? = null,
        val clientIdKey: String = "REAKTOR_WORKER_CLIENT_ID",
        val clientSecretKey: String = "REAKTOR_WORKER_CLIENT_SECRET",
        val scopes: List<String> = emptyList(),
    ) : InfrastructureOperation
}

@Serializable
enum class DatabaseProvider { Postgres, Memgraph, ClickHouse }

@Serializable
enum class DatabaseResultFormat { Csv, QueryReceipt }

@Serializable
sealed interface DatabaseConnection {
    @Serializable
    data object PostgresEnvironment : DatabaseConnection

    @Serializable
    data class KubernetesService(
        val kubeconfig: String,
        val namespace: String,
        val service: String,
        val port: Int,
    ) : DatabaseConnection
}
