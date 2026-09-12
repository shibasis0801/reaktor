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
        require(kubeconfig.isFile && kubeconfig.length() in 1..1_048_576) { "Kubernetes configuration is unavailable" }
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

    fun inspect(namespace: String, action: String, resourceName: String): String {
        require(namespace.matches(Regex("[a-z0-9](?:[a-z0-9.-]{0,61}[a-z0-9])?")))
        if (action != "Inventory") require(resourceName.matches(Regex("[a-z0-9][a-z0-9.-]{0,252}")))
        return when (action) {
            "Inventory" -> {
                val resources = mutableListOf<JsonElement>()
                fun append(page: Any) {
                    resources.addAll(boundedItems(page, 2_000))
                    require(resources.size <= 2_000) { "Select a smaller namespace; inventory exceeds 2,000 objects" }
                }
                append(core.listNamespacedPod(namespace).limit(2_001).execute())
                val apps = AppsV1Api(client)
                append(apps.listNamespacedDeployment(namespace).limit(2_001).execute())
                append(apps.listNamespacedStatefulSet(namespace).limit(2_001).execute())
                append(apps.listNamespacedDaemonSet(namespace).limit(2_001).execute())
                append(core.listNamespacedService(namespace).limit(2_001).execute())
                append(NetworkingV1Api(client).listNamespacedIngress(namespace).limit(2_001).execute())
                val batch = BatchV1Api(client)
                append(batch.listNamespacedJob(namespace).limit(2_001).execute())
                append(batch.listNamespacedCronJob(namespace).limit(2_001).execute())
                append(core.listNamespacedEndpoints(namespace).limit(2_001).execute())
                buildJsonObject { put("items", JsonArray(resources)) }.toString()
            }
            "Events" -> core.listNamespacedEvent(namespace).fieldSelector("involvedObject.name=$resourceName")
                .limit(501).execute().also { boundedItems(it, 500) }.let(JSON::serialize)
            "Logs" -> {
                val containers = core.readNamespacedPod(resourceName, namespace).execute().spec?.containers.orEmpty()
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

    fun secret(namespace: String, name: String, key: String, maxBytes: Int = 32_768): ByteArray {
        val secret = core.readNamespacedSecret(name, namespace).execute()
        return requireNotNull(secret.data?.get(key)) { "Selected credential key is unavailable" }
            .also { require(it.size <= maxBytes) { "Credential exceeds size limit" } }
    }
}

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
        @Synchronized override fun close() { socket?.cancel(); super.close() }
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
