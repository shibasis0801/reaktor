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
        val resourceKind: String = "",
        val resourceUid: String = "",
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
        val parameterized: Boolean = false,
    ) : InfrastructureOperation

    /**
     * One operation against an attached device, executed in-process by the device backends.
     *
     * Device work used to reach the ledger only as an argv vector, which the library-backed
     * backends cannot produce — adblib speaks a protocol and idb speaks gRPC. Describing the call
     * instead of the command line is what lets those backends keep the same discipline every other
     * operation has: a fingerprinted plan, approval bound to that exact plan, and a receipt.
     */
    @Serializable
    data class DeviceCall(
        val transport: String,
        val deviceId: String,
        val deviceName: String = "",
        val action: String,
        val arguments: Map<String, String> = emptyMap(),
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
        val store: WorkerStore? = null,
        val queryFile: String? = null,
        val resultFile: String? = null,
        val maxRows: Int = 100,
        val explain: Boolean = false,
        val catalog: WorkerStoreCatalog? = null,
    ) : InfrastructureOperation
}

@Serializable
enum class DatabaseProvider { Postgres, Memgraph, ClickHouse, PubSub }

@Serializable
enum class DatabaseResultFormat { Csv, QueryReceipt }

@Serializable
sealed interface DatabaseConnection {
    @Serializable
    data class GoogleProject(val project: String, val credentialFile: String, val observations: Map<String, String> = emptyMap(), val observationAuthority: ServiceTokenSource? = null) : DatabaseConnection

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

@Serializable
data class ServiceTokenSource(val tokenEndpoint: String, val audience: String, val authEnvironment: String,
    val credentialFile: String? = null, val clientIdKey: String = "REAKTOR_WORKER_CLIENT_ID",
    val clientSecretKey: String = "REAKTOR_WORKER_CLIENT_SECRET", val scopes: List<String> = emptyList())

fun InfrastructureOperation.WorkerCall.tokenSource() = ServiceTokenSource(tokenEndpoint, audience, authEnvironment,
    credentialFile, clientIdKey, clientSecretKey, scopes)
