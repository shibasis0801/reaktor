package dev.shibasis.reaktor.tooling.operation

import dev.shibasis.reaktor.tooling.SafetyClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import dev.shibasis.reaktor.cloud.clickhouse.SpanReadFilter
import dev.shibasis.reaktor.cloud.clickhouse.SpanRequest
import dev.shibasis.reaktor.cloud.cloudflare.*
import dev.shibasis.reaktor.cloud.database.QueryOptions
import dev.shibasis.reaktor.cloud.google.PubSubProject
import dev.shibasis.reaktor.cloud.http.ServiceTokenRequest
import dev.shibasis.reaktor.cloud.kubernetes.ClusterService

/** Executable provider identities and private payload references; never credentials. */
@Serializable
@SerialName("dev.shibasis.reaktor.tooling.infra.InfrastructureOperation")
sealed interface InfrastructureOperation {
    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.InfrastructureOperation.KubernetesRead")
    data class KubernetesRead(
        val kubeconfig: String,
        val namespace: String,
        val action: String,
        val resourceName: String = "",
        val resourceKind: String = "",
        val resourceUid: String = "",
    ) : InfrastructureOperation

    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.InfrastructureOperation.DatabaseRead")
    data class DatabaseRead(
        val engine: DatabaseProvider,
        val connection: DatabaseConnection,
        val queryFile: String? = null,
        val resultFile: String? = null,
        override val maxRows: Int = 100,
        override val explain: Boolean = false,
        val resultFormat: DatabaseResultFormat = DatabaseResultFormat.Csv,
        override val analyze: Boolean = false,
        val parameterized: Boolean = false,
    ) : InfrastructureOperation, QueryOptions

    /**
     * One operation against an attached device, executed in-process by the device backends.
     *
     * Device work used to reach the ledger only as an argv vector, which the library-backed
     * backends cannot produce — adblib speaks a protocol and idb speaks gRPC. Describing the call
     * instead of the command line is what lets those backends keep the same discipline every other
     * operation has: a fingerprinted plan, approval bound to that exact plan, and a receipt.
     */
    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.InfrastructureOperation.DeviceCall")
    data class DeviceCall(
        val transport: String,
        val deviceId: String,
        val deviceName: String = "",
        val action: String,
        val arguments: Map<String, String> = emptyMap(),
    ) : InfrastructureOperation

    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.InfrastructureOperation.CloudInventoryRead")
    data class CloudInventoryRead(
        val workspace: String,
        val cloudflareAccount: String? = null,
        val kubeconfig: String? = null,
        val googleProject: String? = null,
    ) : InfrastructureOperation

    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.InfrastructureOperation.HttpRead")
    data class HttpRead(val url: String, val accept: String = "application/json", val maxBytes: Int = 65_536) : InfrastructureOperation

    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.InfrastructureOperation.CloudApiCall")
    data class CloudApiCall(
        val workspace: String,
        val operation: String,
        val arguments: String = "{}",
    ) : InfrastructureOperation

    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.InfrastructureOperation.CloudflareAiCall")
    data class CloudflareAiCall(
        override val workspace: String,
        override val account: String,
        override val action: String,
        override val gateway: String? = null,
        override val model: String? = null,
        override val system: String? = null,
        override val prompt: String? = null,
    ) : InfrastructureOperation, CloudflareAiRequest

    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.InfrastructureOperation.IslandShapesRead")
    data class IslandShapesRead(
        val hosts: List<String>,
        override val tokenEndpoint: String,
        override val audience: String,
        override val authEnvironment: String,
        override val credentialFile: String? = null,
        override val clientIdKey: String = "REAKTOR_WORKER_CLIENT_ID",
        override val clientSecretKey: String = "REAKTOR_WORKER_CLIENT_SECRET",
        override val scopes: List<String> = emptyList(),
    ) : InfrastructureOperation, ServiceTokenRequest

    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.InfrastructureOperation.SpanRead")
    data class SpanRead(
        override val connection: DatabaseConnection.KubernetesService,
        override val database: String,
        override val windowMinutes: Int = 60,
        override val limit: Int = 500,
        override val filter: SpanReadFilter = SpanReadFilter(),
    ) : InfrastructureOperation, SpanRequest

    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.InfrastructureOperation.WorkerCall")
    data class WorkerCall(
        override val endpoint: String,
        override val operation: String,
        override val tokenEndpoint: String,
        override val audience: String,
        override val authEnvironment: String,
        override val credentialFile: String? = null,
        override val clientIdKey: String = "REAKTOR_WORKER_CLIENT_ID",
        override val clientSecretKey: String = "REAKTOR_WORKER_CLIENT_SECRET",
        override val scopes: List<String> = emptyList(),
        override val store: WorkerStore? = null,
        override val queryFile: String? = null,
        override val resultFile: String? = null,
        override val maxRows: Int = 100,
        override val explain: Boolean = false,
        override val catalog: WorkerStoreCatalog? = null,
        val expectedEffect: SafetyClass = SafetyClass.LiveRead,
        override val inputFile: String? = null,
        override val inputDigest: String? = null,
    ) : InfrastructureOperation, WorkerRequest {
        override val effect: String get() = expectedEffect.workerEffect()
    }
}

@Serializable
enum class DatabaseProvider { Postgres, Memgraph, ClickHouse, PubSub, D1, SQLite }

@Serializable
enum class DatabaseResultFormat { Csv, QueryReceipt }

@Serializable
@SerialName("dev.shibasis.reaktor.tooling.infra.DatabaseConnection")
sealed interface DatabaseConnection {
    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.DatabaseConnection.GoogleProject")
    data class GoogleProject(override val project: String, override val credentialFile: String, override val observations: Map<String, String> = emptyMap(), override val observationAuthority: ServiceTokenSource? = null) : DatabaseConnection, PubSubProject

    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.DatabaseConnection.PostgresEnvironment")
    data object PostgresEnvironment : DatabaseConnection

    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.DatabaseConnection.KubernetesService")
    data class KubernetesService(
        override val kubeconfig: String,
        override val namespace: String,
        override val service: String,
        override val port: Int,
    ) : DatabaseConnection, ClusterService

    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.DatabaseConnection.CloudflareD1")
    data class CloudflareD1(override val workspace: String, override val account: String, val database: String, override val databaseId: String, val config: String) : DatabaseConnection, D1Connection

    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.DatabaseConnection.SQLiteFile")
    data class SQLiteFile(val path: String, val ownerOnly: Boolean = false) : DatabaseConnection

    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.DatabaseConnection.SQLiteDirectory")
    data class SQLiteDirectory(val path: String) : DatabaseConnection

    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.DatabaseConnection.AndroidAppDatabase")
    data class AndroidAppDatabase(val serial: String, val application: String, val path: String) : DatabaseConnection

    @Serializable
    @SerialName("dev.shibasis.reaktor.tooling.infra.DatabaseConnection.SimulatorAppDatabase")
    data class SimulatorAppDatabase(val udid: String, val application: String, val path: String) : DatabaseConnection
}

@Serializable
data class ServiceTokenSource(override val tokenEndpoint: String, override val audience: String, override val authEnvironment: String,
    override val credentialFile: String? = null, override val clientIdKey: String = "REAKTOR_WORKER_CLIENT_ID",
    override val clientSecretKey: String = "REAKTOR_WORKER_CLIENT_SECRET", override val scopes: List<String> = emptyList()) : ServiceTokenRequest

@Serializable
data class IslandReading(val host: String, val shape: kotlinx.serialization.json.JsonElement? = null, val failure: String? = null)
