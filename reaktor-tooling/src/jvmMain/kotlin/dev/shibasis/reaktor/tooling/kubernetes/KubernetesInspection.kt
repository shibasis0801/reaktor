package dev.shibasis.reaktor.tooling.kubernetes

import kotlinx.serialization.json.*

enum class KubernetesReadAction { Inventory, Events, Logs }

data class KubernetesObservedObject(
    val kind: String, val name: String, val namespace: String,
    val uid: String, val resourceVersion: String, val createdAt: String,
    val phase: String, val ready: String, val restarts: Int,
    val node: String, val addresses: List<String>, val images: List<String>,
    val owners: List<String>, val conditions: List<String>, val ports: List<String>,
) { val key get() = "$namespace/$kind/$name" }

object KubernetesInspection {
    private val identity = Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")

    fun command(executable: String, kubeconfig: String, namespace: String,
        action: KubernetesReadAction, resourceName: String = ""): List<String> {
        require(namespace.length <= 63 && identity.matches(namespace)) { "Enter a Kubernetes namespace" }
        if (action != KubernetesReadAction.Inventory) require(resourceName.length <= 253 && identity.matches(resourceName)) {
            "Select an exact resource name"
        }
        return listOf(executable, "--kubeconfig", kubeconfig, "--namespace", namespace, "--request-timeout=15s") + when (action) {
            KubernetesReadAction.Inventory -> listOf("get", "pods,deployments,statefulsets,daemonsets,services,ingresses,jobs,cronjobs,endpoints", "-o", "json")
            KubernetesReadAction.Events -> listOf("get", "events", "--field-selector=involvedObject.name=$resourceName", "-o", "json")
            KubernetesReadAction.Logs -> listOf("logs", "pod/$resourceName", "--all-containers=true", "--tail=300", "--limit-bytes=262144", "--timestamps=true")
        }
    }

    /** Explicit status projection: literal environment values and secret content are excluded. */
    fun inventory(output: String): List<KubernetesObservedObject> {
        require(output.length <= 8 * 1024 * 1024) { "Kubernetes inventory exceeds 8 MiB" }
        val root = Json.parseToJsonElement(output).jsonObject
        val items = root["items"] as? JsonArray ?: error("Kubernetes returned no resource list")
        require(items.size <= 2000) { "Select a smaller namespace; inventory exceeds 2,000 resources" }
        return items.map { value ->
            val item = value.jsonObject
            val metadata = item.obj("metadata")
            val spec = item.obj("spec")
            val status = item.obj("status")
            val kind = item.string("kind")
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
                spec.objects("ports").map { "${it.string("name")} ${it.string("port")} → ${it.string("targetPort")} / ${it.string("protocol")}" })
        }.sortedWith(compareBy(KubernetesObservedObject::kind, KubernetesObservedObject::name))
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.objects(key: String) = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
