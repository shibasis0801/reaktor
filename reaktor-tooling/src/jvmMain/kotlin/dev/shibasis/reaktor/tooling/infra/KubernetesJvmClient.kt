package dev.shibasis.reaktor.tooling.infra

import io.kubernetes.client.PortForward
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.JSON
import io.kubernetes.client.openapi.Pair
import io.kubernetes.client.openapi.apis.AppsV1Api
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.apis.NetworkingV1Api
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import io.kubernetes.client.util.WebSockets
import io.kubernetes.client.util.WebSocketStreamHandler
import okhttp3.WebSocket
import kotlinx.serialization.json.*
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

class KubernetesJvmClient(kubeconfig: File, private val session: InfrastructureSession) {
    private val client = run {
        if (!kubeconfig.isFile || !kubeconfig.canRead()) throw MissingKubeconfig(kubeconfig.path)
        require(kubeconfig.length() in 1..1_048_576) { "Kubernetes configuration is unavailable" }
        val content = kubeconfig.readText()
        val yaml = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any?>>(content)
        val users = yaml["users"] as? List<*> ?: emptyList<Any>()
        require(users.none { entry ->
            val user = (entry as? Map<*, *>)?.get("user") as? Map<*, *>
            user?.containsKey("exec") == true || user?.containsKey("auth-provider") == true
        }) { "This JVM connection requires certificate or token Kubernetes credentials; executable auth plugins are unsupported" }
        val config = KubeConfig.loadKubeConfig(content.reader()).apply { setFile(kubeconfig) }
        ClientBuilder.kubeconfig(config).setReadTimeout(Duration.ofSeconds(15)).build().also { api ->
            api.httpClient = api.httpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(false).followSslRedirects(false).build()
            session.own(AutoCloseable {
                api.httpClient.dispatcher.cancelAll()
                api.httpClient.connectionPool.evictAll()
                api.httpClient.dispatcher.executorService.shutdownNow()
                api.httpClient.cache?.close()
            })
        }
    }
    private val core = CoreV1Api(client)

    fun inspect(namespace: String, action: String, resourceName: String, resourceKind: String = "", resourceUid: String = ""): String {
        require(namespace.matches(Regex("[a-z0-9](?:[a-z0-9.-]{0,61}[a-z0-9])?")))
        if (action != "Inventory") require(resourceName.matches(Regex("[a-z0-9][a-z0-9.-]{0,252}")))
        require(resourceKind.isEmpty() || resourceKind.matches(Regex("[A-Za-z][A-Za-z0-9]{0,63}")))
        require(resourceUid.isEmpty() || resourceUid.matches(Regex("[A-Za-z0-9-]{1,128}")))
        return when (action) {
            "Inventory" -> {
                val resources = mutableListOf<JsonElement>()
                fun append(page: Any, kind: String, apiVersion: String) {
                    resources.addAll(boundedItems(page, 2_000).map { value ->
                        val item = value.jsonObject
                        require(item["kind"]?.jsonPrimitive?.contentOrNull.orEmpty().let { it.isBlank() || it == kind }) {
                            "Kubernetes returned an unexpected resource kind"
                        }
                        JsonObject(item + mapOf("kind" to JsonPrimitive(kind), "apiVersion" to JsonPrimitive(apiVersion)))
                    })
                    require(resources.size <= 2_000) { "Select a smaller namespace; inventory exceeds 2,000 objects" }
                }
                append(core.listNamespacedPod(namespace).limit(2_001).execute(), "Pod", "v1")
                val apps = AppsV1Api(client)
                append(apps.listNamespacedDeployment(namespace).limit(2_001).execute(), "Deployment", "apps/v1")
                append(apps.listNamespacedStatefulSet(namespace).limit(2_001).execute(), "StatefulSet", "apps/v1")
                append(apps.listNamespacedDaemonSet(namespace).limit(2_001).execute(), "DaemonSet", "apps/v1")
                append(core.listNamespacedService(namespace).limit(2_001).execute(), "Service", "v1")
                append(NetworkingV1Api(client).listNamespacedIngress(namespace).limit(2_001).execute(), "Ingress", "networking.k8s.io/v1")
                val batch = BatchV1Api(client)
                append(batch.listNamespacedJob(namespace).limit(2_001).execute(), "Job", "batch/v1")
                append(batch.listNamespacedCronJob(namespace).limit(2_001).execute(), "CronJob", "batch/v1")
                append(core.listNamespacedEndpoints(namespace).limit(2_001).execute(), "Endpoints", "v1")
                val server = java.net.URI(client.basePath)
                buildJsonObject {
                    put("clusterId", "${server.scheme}://${server.host}:${server.port}")
                    put("items", JsonArray(resources))
                }.toString()
            }
            "Events" -> core.listNamespacedEvent(namespace).fieldSelector(buildList {
                add("involvedObject.name=$resourceName")
                if (resourceKind.isNotEmpty()) add("involvedObject.kind=$resourceKind")
                if (resourceUid.isNotEmpty()) add("involvedObject.uid=$resourceUid")
            }.joinToString(","))
                .limit(501).execute().also { boundedItems(it, 500) }.let(JSON::serialize)
            "Logs" -> {
                require(resourceKind.isEmpty() || resourceKind == "Pod") { "Logs require a Pod" }
                val pod = core.readNamespacedPod(resourceName, namespace).execute()
                require(resourceUid.isEmpty() || pod.metadata?.uid == resourceUid) { "Pod was replaced; refresh the inventory before reading logs" }
                val containers = pod.spec?.containers.orEmpty()
                require(containers.size in 1..32) { "Pod must contain between 1 and 32 containers" }
                containers.joinToString("\n") { container ->
                    "[${container.name}]\n" + core.readNamespacedPodLog(resourceName, namespace)
                        .container(container.name).timestamps(true).limitBytes(262_144).tailLines(300).execute()
                }
            }
            else -> error("Unknown Kubernetes read operation")
        }.also { require(it.toByteArray().size <= 8_388_608) { "Kubernetes response exceeds 8 MiB" } }
    }

    fun portForward(namespace: String, service: String, port: Int): KubernetesTunnel {
        require(port in 1..65535)
        val spec = requireNotNull(core.readNamespacedService(service, namespace).execute().spec) { "Service has no specification" }
        val servicePort = requireNotNull(spec.ports.orEmpty().singleOrNull { it.port == port && (it.protocol ?: "TCP") == "TCP" }) {
            "Service does not expose the selected TCP port"
        }
        val selector = requireNotNull(spec.selector?.takeIf { it.isNotEmpty() }) { "Service has no pod selector" }
            .toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
        val pods = core.listNamespacedPod(namespace).labelSelector(selector).limit(101).execute()
        require(pods.items.size <= 100 && pods.metadata?.`continue`.isNullOrBlank()) { "Service selects too many pods" }
        val pod = requireNotNull(pods.items.filter { pod ->
            pod.metadata?.deletionTimestamp == null && pod.status?.phase == "Running" &&
                pod.status?.conditions.orEmpty().any { it.type == "Ready" && it.status == "True" }
        }.minByOrNull { it.metadata?.name.orEmpty() }) { "Service has no ready pod" }
        val target = servicePort.targetPort
        val targetPort = when {
            target == null -> port
            target.isInteger -> target.intValue
            else -> requireNotNull(pod.spec?.containers.orEmpty().flatMap { it.ports.orEmpty() }
                .filter { it.name == target.strValue && (it.protocol ?: "TCP") == "TCP" }
                .map { it.containerPort }.distinct().singleOrNull()) { "Service target port is unavailable on the selected pod" }
        }
        require(targetPort in 1..65535)
        return session.own(KubernetesTunnel(client, namespace, requireNotNull(pod.metadata?.name), targetPort))
    }

    fun cluster(): JsonObject {
        val apps = AppsV1Api(client)
        val batch = BatchV1Api(client)
        val lists = listOf<Triple<String, String, () -> Any>>(
            Triple("Node", "v1") { core.listNode().limit(ClusterLimit + 1).execute() },
            Triple("Pod", "v1") { core.listPodForAllNamespaces().limit(ClusterLimit + 1).execute() },
            Triple("Deployment", "apps/v1") { apps.listDeploymentForAllNamespaces().limit(ClusterLimit + 1).execute() },
            Triple("StatefulSet", "apps/v1") { apps.listStatefulSetForAllNamespaces().limit(ClusterLimit + 1).execute() },
            Triple("DaemonSet", "apps/v1") { apps.listDaemonSetForAllNamespaces().limit(ClusterLimit + 1).execute() },
            Triple("ReplicaSet", "apps/v1") { apps.listReplicaSetForAllNamespaces().limit(ClusterLimit + 1).execute() },
            Triple("CronJob", "batch/v1") { batch.listCronJobForAllNamespaces().limit(ClusterLimit + 1).execute() },
            Triple("Job", "batch/v1") { batch.listJobForAllNamespaces().limit(ClusterLimit + 1).execute() },
            Triple("Service", "v1") { core.listServiceForAllNamespaces().limit(ClusterLimit + 1).execute() },
            Triple("PersistentVolumeClaim", "v1") { core.listPersistentVolumeClaimForAllNamespaces().limit(ClusterLimit + 1).execute() },
            Triple("PersistentVolume", "v1") { core.listPersistentVolume().limit(ClusterLimit + 1).execute() },
        )
        val items = lists.flatMap { (kind, apiVersion, read) ->
            boundedItems(read(), ClusterLimit).map { JsonObject(it.jsonObject + mapOf("kind" to JsonPrimitive(kind), "apiVersion" to JsonPrimitive(apiVersion))) }
        }
        return buildJsonObject { put("items", JsonArray(items)) }
    }

    fun events(): JsonObject {
        val items = boundedItems(core.listEventForAllNamespaces().limit(ClusterLimit + 1).execute(), ClusterLimit)
        return buildJsonObject { put("items", JsonArray(items.map { JsonObject(it.jsonObject + mapOf("kind" to JsonPrimitive("Event"), "apiVersion" to JsonPrimitive("v1"))) })) }
    }

    fun metrics(kind: String): JsonElement {
        require(kind in setOf("pods", "nodes"))
        val call = client.buildCall(client.basePath, "/apis/metrics.k8s.io/v1beta1/$kind", "GET", emptyList(), emptyList(), null,
            mutableMapOf("Accept" to "application/json"), mutableMapOf(), mutableMapOf(), arrayOf("BearerToken"), null)
        return call.execute().use { response ->
            check(response.isSuccessful) { "Kubernetes metrics answered HTTP ${response.code}" }
            val body = requireNotNull(response.body).byteStream().readNBytes(8_388_609)
            require(body.size <= 8_388_608) { "Kubernetes metrics exceed 8 MiB" }
            Json.parseToJsonElement(body.toString(Charsets.UTF_8))
        }
    }

    fun secret(namespace: String, name: String, key: String, maxBytes: Int = 32_768): ByteArray {
        val secret = core.readNamespacedSecret(name, namespace).execute()
        return requireNotNull(secret.data?.get(key)) { "Selected credential key is unavailable" }
            .also { require(it.size <= maxBytes) { "Credential exceeds size limit" } }
    }
}

private const val ClusterLimit = 5_000

private fun boundedItems(page: Any, limit: Int): JsonArray {
    val json = Json.parseToJsonElement(JSON.serialize(page)).jsonObject
    val items = requireNotNull(json["items"]?.jsonArray) { "Kubernetes list has no items" }
    require(items.size <= limit && json["metadata"]?.jsonObject?.get("continue")?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
        "Kubernetes list exceeds the $limit object limit"
    }
    return items
}

/** Each local connection owns one pod port-forward stream pair and closes with the operation. */
class KubernetesTunnel internal constructor(client: ApiClient, namespace: String, pod: String, port: Int) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val resources = InfrastructureSession()
    private val server = resources.own(ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")))
    private val workers = Executors.newVirtualThreadPerTaskExecutor()
    private val capacity = Semaphore(16)
    val localPort: Int get() = server.localPort

    init {
        workers.submit {
            while (!closed.get()) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                if (!capacity.tryAcquire()) { socket.close(); continue }
                val connection = InfrastructureSession()
                try {
                    connection.own(socket)
                    resources.own(connection)
                    workers.submit {
                        try {
                            val forward = openForward(client, namespace, pod, port, connection)
                            workers.submit {
                                try { forward.getInputStream(port).transferTo(socket.getOutputStream()) }
                                catch (_: Exception) { /* The peer may close either half first. */ }
                                finally { connection.close() }
                            }
                            socket.getInputStream().transferTo(forward.getOutboundStream(port))
                        } catch (_: Exception) {
                            // Closing the socket reports stream failure without exposing provider credentials.
                        } finally { connection.close(); capacity.release() }
                    }
                } catch (_: Exception) { connection.close(); capacity.release() }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            resources.close()
            workers.shutdownNow()
        }
    }
}

private fun openForward(client: ApiClient, namespace: String, pod: String, port: Int, session: InfrastructureSession): PortForward.PortForwardResult {
    // Own the official handler before the handshake: PortForward.forward only returns after
    // the remote port preface, leaving an interrupted handshake without a closeable result.
    val handler = session.own(object : WebSocketStreamHandler() {
        private var socket: WebSocket? = null
        @Synchronized override fun open(protocol: String, socket: WebSocket) {
            if (isClosed) socket.cancel() else { this.socket = socket; super.open(protocol, socket) }
        }
        @Synchronized override fun close() {
            // The official handler drains its outbound queue before closing. Cancelling first
            // strands queued bytes and makes a successful read wait for the flush timeout.
            try { super.close() } finally { socket?.cancel() }
        }
    })
    val result = session.own(PortForward.PortForwardResult(handler, listOf(port)))
    session.own(handler.getInputStream(0))
    session.own(handler.getInputStream(1))
    WebSockets.stream("/api/v1/namespaces/$namespace/pods/$pod/portforward", "GET",
        listOf(Pair("ports", port.toString())), client, handler)
    handler.waitForInitialized()
    check(handler.error == null && !handler.isClosed) { "Kubernetes port-forward handshake failed" }
    result.init()
    return result
}
