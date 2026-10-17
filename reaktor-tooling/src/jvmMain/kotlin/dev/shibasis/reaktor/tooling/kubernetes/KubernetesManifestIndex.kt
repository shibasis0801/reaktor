package dev.shibasis.reaktor.tooling.kubernetes

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Desired workload facts only. Secret values and arbitrary environment values never leave this reader. */
data class KubernetesManifestObject(
    val kind: String,
    val name: String,
    val namespace: String,
    val images: List<String>,
    val replicas: Int?,
    val ports: List<Int>,
    val labels: Map<String, String>,
    val selector: Map<String, String>,
    val serviceReferences: List<String>,
    val secretReferences: List<String>,
    val configMapReferences: List<String>,
    val resources: Map<String, String>,
)

object KubernetesManifestIndex {
    fun read(yaml: String): List<KubernetesManifestObject> {
        val options = LoaderOptions().apply {
            codePointLimit = 2_000_000
            maxAliasesForCollections = 20
            nestingDepthLimit = 40
            isAllowDuplicateKeys = false
        }
        return Yaml(SafeConstructor(options)).loadAll(yaml).mapNotNull { value ->
            val doc = value as? Map<*, *> ?: return@mapNotNull null
            val kind = doc["kind"] as? String ?: return@mapNotNull null
            if (kind !in setOf("Deployment", "StatefulSet", "DaemonSet", "Service", "Ingress", "Job", "CronJob")) return@mapNotNull null
            val metadata = doc.map("metadata")
            val name = metadata["name"] as? String ?: return@mapNotNull null
            val spec = doc.map("spec")
            val template = if (kind == "CronJob") spec.map("jobTemplate").map("spec").map("template") else spec.map("template")
            val pod = template.map("spec")
            val containers = pod.maps("containers") + pod.maps("initContainers")
            val refs = containers.flatMap { container -> container.maps("env").map { it.map("valueFrom") } }
            val envFrom = containers.flatMap { it.maps("envFrom") }
            val volumes = pod.maps("volumes")
            val services = buildList {
                containers.flatMap { it.maps("env") }.forEach { env ->
                    // Only explicit *_HOST declarations are connection evidence. Never expose other values.
                    if ((env["name"] as? String)?.endsWith("_HOST") == true) {
                        (env["value"] as? String)?.takeIf { it.matches(Regex("[a-z0-9][a-z0-9.-]*")) }?.let(::add)
                    }
                }
                fun backend(map: Map<*, *>) { (map.map("service")["name"] as? String)?.let(::add) }
                backend(spec.map("defaultBackend"))
                spec.maps("rules").flatMap { it.map("http").maps("paths") }.forEach { backend(it.map("backend")) }
            }
            KubernetesManifestObject(
                kind, name, metadata["namespace"] as? String ?: "default",
                containers.mapNotNull { it["image"] as? String }, (spec["replicas"] as? Number)?.toInt(),
                (containers.flatMap { it.maps("ports") }.mapNotNull { (it["containerPort"] as? Number)?.toInt() } +
                    spec.maps("ports").mapNotNull { (it["port"] as? Number)?.toInt() }).distinct(),
                (if (kind == "Service" || kind == "Ingress") metadata else template.map("metadata")).map("labels").strings(),
                (if (kind == "Service") spec.map("selector") else spec.map("selector").map("matchLabels")).strings(),
                services.distinct(),
                (refs.mapNotNull { it.map("secretKeyRef")["name"] as? String } +
                    envFrom.mapNotNull { it.map("secretRef")["name"] as? String } +
                    volumes.mapNotNull { it.map("secret")["secretName"] as? String } +
                    pod.maps("imagePullSecrets").mapNotNull { it["name"] as? String }).distinct(),
                (refs.mapNotNull { it.map("configMapKeyRef")["name"] as? String } +
                    envFrom.mapNotNull { it.map("configMapRef")["name"] as? String } +
                    volumes.mapNotNull { it.map("configMap")["name"] as? String }).distinct(),
                buildMap { containers.forEach { container ->
                    listOf("requests", "limits").forEach { bound ->
                        container.map("resources").map(bound).strings().forEach { (key, value) ->
                            put("${container["name"]}.$bound.$key", value)
                        }
                    }
                } },
            )
        }.toList()
    }

    private fun Map<*, *>.map(key: String): Map<*, *> = this[key] as? Map<*, *> ?: emptyMap<Any, Any>()
    private fun Map<*, *>.maps(key: String): List<Map<*, *>> = (this[key] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
    private fun Map<*, *>.strings(): Map<String, String> = entries.mapNotNull { (key, value) ->
        (key as? String)?.let { it to value.toString() }
    }.toMap()
}
