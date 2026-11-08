package dev.shibasis.reaktor.cloud.kubernetes

import dev.shibasis.reaktor.cloud.api.PlannedRequest
import dev.shibasis.reaktor.cloud.api.WritePlan
import dev.shibasis.reaktor.cloud.api.WritePlans
import dev.shibasis.reaktor.cloud.api.WriteReceipt
import dev.shibasis.reaktor.cloud.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class KubernetesApplyFailure(val status: Int, message: String) : Exception(message)

class KubernetesApply(private val cluster: KubernetesJvmClient, private val manager: String = "reaktor") {
    private val resources = HashMap<String, List<JsonObject>>()

    fun plan(manifests: String): List<WritePlan> = documents(manifests).map(::plan)

    fun plan(manifest: JsonObject): WritePlan {
        val path = path(manifest)
        val body = Json.encodeToString(JsonElement.serializer(), manifest)
        val live = read("GET", path)
        val dryRun = read("PATCH", "$path?fieldManager=$manager&force=true&dryRun=All", body)
            ?: throw KubernetesApplyFailure(404, "Kubernetes refused a dry run for ${target(manifest)}")
        return WritePlans.plan(target(manifest), PlannedRequest("PATCH", "${cluster.server}$path?fieldManager=$manager&force=true", manifest),
            live, dryRun, Bookkeeping, live.obj("metadata").text("resourceVersion"))
    }

    fun apply(plan: WritePlan, approved: String): WriteReceipt {
        WritePlans.confirm(plan, approved)
        check(plan.request.url.startsWith(cluster.server + "/")) { "${plan.target} was planned against another cluster" }
        val (status, text) = cluster.request(plan.request.method, plan.request.url.removePrefix(cluster.server), plan.request.body.toString(), ApplyPatch)
        if (status !in 200..299) throw KubernetesApplyFailure(status, failure(plan.request.method, plan.target, status, text))
        val applied = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        return WriteReceipt(plan.target, approved, status, applied.obj("metadata").text("resourceVersion"))
    }

    fun documents(manifests: String): List<JsonObject> = yamlDocuments(manifests).filterIsInstance<JsonObject>().flatMap { document ->
        if (document.text("kind") == "List") (document["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>() else listOf(document)
    }

    private fun read(method: String, path: String, body: String? = null): JsonObject? {
        val (status, text) = cluster.request(method, path, body, ApplyPatch)
        if (status == 404 && method == "GET") return null
        if (status !in 200..299) throw KubernetesApplyFailure(status, failure(method, path.substringBefore('?'), status, text))
        return Json.parseToJsonElement(text) as? JsonObject
    }

    private fun path(manifest: JsonObject): String {
        val apiVersion = requireNotNull(manifest.text("apiVersion")) { "A manifest needs an apiVersion" }
        val kind = requireNotNull(manifest.text("kind")) { "A manifest needs a kind" }
        val name = requireNotNull(manifest.obj("metadata").text("name")) { "A $kind needs metadata.name" }
        val base = if ('/' in apiVersion) "/apis/$apiVersion" else "/api/$apiVersion"
        val resource = resources.getOrPut(apiVersion) { read("GET", base).objects("resources") }
            .firstOrNull { it.text("kind") == kind && '/' !in it.text("name").orEmpty() }
            ?: throw KubernetesApplyFailure(404, "The cluster serves no $kind in $apiVersion")
        val namespace = manifest.obj("metadata").text("namespace") ?: "default"
        val scope = if (resource.flag("namespaced") == true) "/namespaces/$namespace" else ""
        return "$base$scope/${resource.text("name")}/$name"
    }

    private fun target(manifest: JsonObject): String {
        val metadata = manifest.obj("metadata")
        return "k8s:" + listOfNotNull(metadata.text("namespace"), manifest.text("kind"), metadata.text("name")).joinToString("/")
    }

    private fun failure(method: String, what: String, status: Int, text: String) =
        "Kubernetes answered HTTP $status for $method $what: ${runCatching { Json.parseToJsonElement(text).text("message") }.getOrNull().orEmpty()}".trim()

    companion object {
        const val ApplyPatch = "application/apply-patch+yaml"
        val Bookkeeping = setOf("metadata.managedFields", "metadata.resourceVersion", "metadata.generation", "metadata.creationTimestamp",
            "metadata.uid", "status")
    }
}
