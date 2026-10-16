package dev.shibasis.reaktor.tooling.kubernetes

import kotlinx.serialization.json.*

enum class KubernetesReadAction { Inventory, Events, Logs }

data class KubernetesObservedObject(
    val kind: String, val name: String, val namespace: String,
    val uid: String, val resourceVersion: String, val createdAt: String,
    val phase: String, val ready: String, val restarts: Int,
    val node: String, val addresses: List<String>, val images: List<String>,
    val owners: List<String>, val conditions: List<String>, val ports: List<String>,
    val apiVersion: String = "", val clusterId: String = "",
) {
    val key get() = if (uid.isNotBlank()) "$clusterId/uid/$uid"
        else "$clusterId/${apiVersion.substringBeforeLast('/', "core")}/$namespace/$kind/$name"
}

object KubernetesInspection {
    /** Explicit status projection: literal environment values and secret content are excluded. */
    fun inventory(output: String): List<KubernetesObservedObject> {
        require(output.length <= 8 * 1024 * 1024) { "Kubernetes inventory exceeds 8 MiB" }
        val root = Json.parseToJsonElement(output).jsonObject
        val items = root["items"] as? JsonArray ?: error("Kubernetes returned no resource list")
        require(items.size <= 2000) { "Select a smaller namespace; inventory exceeds 2,000 resources" }
        val observed = items.map { value ->
            val item = value.jsonObject
            val metadata = item.obj("metadata")
            val spec = item.obj("spec")
            val status = item.obj("status")
            val kind = item.string("kind")
            require(kind.isNotBlank() && metadata.string("name").isNotBlank()) {
                "Kubernetes inventory contains an object without a kind or name; refresh the inventory"
            }
            val containerStatus = status.objects("containerStatuses")
            val pod = if (kind == "Pod") spec else spec.obj("template").obj("spec")
            val desired = spec.string("replicas")
            val ready = if (kind == "Pod") "${containerStatus.count { it.string("ready") == "true" }}/${containerStatus.size} containers"
                else if (desired.isNotEmpty()) "${status.string("readyReplicas").ifBlank { "0" }}/$desired replicas" else ""
            KubernetesObservedObject(kind, metadata.string("name"), metadata.string("namespace"), metadata.string("uid"),
                metadata.string("resourceVersion"), metadata.string("creationTimestamp"), status.string("phase"), ready,
                containerStatus.sumOf { it.string("restartCount").toIntOrNull() ?: 0 }, spec.string("nodeName"),
                listOf(status.string("podIP"), spec.string("clusterIP")).filter { it.isNotBlank() && it != "None" } +
                    status.obj("loadBalancer").objects("ingress").flatMap { listOf(it.string("ip"), it.string("hostname")) }.filter(String::isNotBlank),
                pod.objects("containers").map { it.string("image") }.filter(String::isNotBlank),
                metadata.objects("ownerReferences").map { "${it.string("kind")}/${it.string("name")}" },
                status.objects("conditions").map { "${it.string("type")}: ${it.string("status")} · ${it.string("reason")}" } +
                    containerStatus.flatMap { container -> container.obj("state").entries.map { (state, detail) ->
                        "${container.string("name")}: $state · ${(detail as? JsonObject)?.string("reason").orEmpty()}"
                    } },
                spec.objects("ports").map { "${it.string("name")} ${it.string("port")} → ${it.string("targetPort")} / ${it.string("protocol")}" },
                item.string("apiVersion"), root.string("clusterId"))
        }.sortedWith(compareBy(KubernetesObservedObject::kind, KubernetesObservedObject::name))
        require(observed.map { it.key }.distinct().size == observed.size) {
            "Kubernetes inventory contains duplicate resource identities; refresh the inventory"
        }
        return observed
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.objects(key: String) = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
