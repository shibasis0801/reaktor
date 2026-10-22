package dev.shibasis.reaktor.cloud.observability

import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.yaml.snakeyaml.Yaml

object ObservabilitySources {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun prometheusRules(files: Map<String, String>): String {
        val rules = files.filterKeys { it.startsWith("alerts/") && it.endsWith(".json") }.toSortedMap().values.mapNotNull { text ->
            val rule = json.parseToJsonElement(text).jsonObject
            val uid = rule.getValue("uid").jsonPrimitive.content
            if (uid == "reaktor-grafana-token-expiry") return@mapNotNull null
            val expressions = rule.getValue("data").jsonArray.associate { query ->
                query.jsonObject.getValue("refId").jsonPrimitive.content to query.jsonObject.getValue("model").jsonObject
            }
            val threshold = expressions.getValue(rule.getValue("condition").jsonPrimitive.content)
            val query = expressions.getValue(threshold.getValue("expression").jsonPrimitive.content).getValue("expr").jsonPrimitive.content
            val evaluator = threshold.getValue("conditions").jsonArray.first().jsonObject.getValue("evaluator").jsonObject
            val operator = when (evaluator.getValue("type").jsonPrimitive.content) {
                "gt" -> ">"
                "lt" -> "<"
                else -> error("Unsupported alert evaluator")
            }
            val annotations = rule.getValue("annotations").jsonObject
            buildJsonObject {
                put("alert", uid.replace('-', '_'))
                val expression = if (uid.endsWith("-token-expiry")) "vector($query)" else query
                put("expr", "($expression) $operator ${evaluator.getValue("params").jsonArray.first().jsonPrimitive.content}")
                put("for", rule.getValue("for")); put("labels", rule.getValue("labels"))
                put("annotations", buildJsonObject {
                    annotations.filterKeys { !it.startsWith("__") }.forEach { (key, value) -> put(key, value) }
                    put("dashboard", "https://telemetry.reaktor.build/d/" + (annotations["__dashboardUid__"]?.jsonPrimitive?.content ?: "reaktor-stack-overview"))
                })
            }
        }
        require(rules.isNotEmpty())
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("groups", buildJsonArray { add(buildJsonObject {
                put("name", "reaktor-stack-health"); put("interval", "60s"); put("rules", JsonArray(rules))
            }) })
        }) + "\n"
    }

    fun dashboards(files: Map<String, String>): Map<String, JsonObject> {
        val entries = files.filterKeys { it.startsWith("dashboards/") && it.endsWith(".json") }.toSortedMap().values.map { text ->
            val dashboard = json.parseToJsonElement(text).jsonObject
            dashboard.getValue("uid").jsonPrimitive.content to dashboard
        }
        require(entries.isNotEmpty() && entries.map { it.first }.distinct().size == entries.size)
        return entries.toMap()
    }

    fun alloyValues(files: Map<String, String>): Map<String, Any> {
        val values = yaml(files.getValue("alloy-values.yaml")).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val alloy = (values.getValue("alloy") as Map<String, Any>).toMutableMap()
        alloy["configMap"] = mapOf("content" to files.getValue("config.alloy"))
        values["alloy"] = alloy
        return values
    }

    fun kubeStateMetricsValues(files: Map<String, String>) = yaml(files.getValue("kube-state-metrics-values.yaml"))

    fun manifests(files: Map<String, String>, cloudflareConfiguration: String): List<Map<String, Any>> {
        fun configMap(name: String, data: Map<String, String>): Map<String, Any> = mapOf(
            "apiVersion" to "v1", "kind" to "ConfigMap", "metadata" to mapOf("name" to name, "namespace" to "observability"), "data" to data)
        val config = listOf("prometheus.yml", "loki.yml", "tempo.yml", "alertmanager.yml").associateWith(files::getValue) + ("rules.yml" to prometheusRules(files))
        val maps = listOf(configMap("telemetry-store", config), configMap("memgraph-storage-probe", mapOf("probe.sh" to files.getValue("memgraph-storage-probe.sh"))),
            configMap("cloudflare-metrics", mapOf("config.yaml" to cloudflareConfiguration)))
        val resources = listOf("backends.yaml", "memgraph-storage-probe.yaml", "cloudflare-exporter.yaml").flatMap { path ->
            Yaml().loadAll(files.getValue(path)).filterNotNull().map { value ->
                @Suppress("UNCHECKED_CAST")
                val resource = value as Map<String, Any>
                if (resource["kind"] != "Deployment") return@map resource
                @Suppress("UNCHECKED_CAST")
                val metadata = resource.getValue("metadata") as Map<String, Any>
                val content = when (metadata["name"]) {
                    "memgraph-storage-probe" -> files.getValue("memgraph-storage-probe.sh")
                    "cloudflare-metrics" -> cloudflareConfiguration
                    else -> return@map resource
                }
                @Suppress("UNCHECKED_CAST")
                val spec = (resource.getValue("spec") as Map<String, Any>).toMutableMap()
                @Suppress("UNCHECKED_CAST")
                val template = (spec.getValue("template") as Map<String, Any>).toMutableMap()
                @Suppress("UNCHECKED_CAST")
                val podMetadata = (template.getValue("metadata") as Map<String, Any>).toMutableMap()
                @Suppress("UNCHECKED_CAST")
                val annotations = (podMetadata["annotations"] as? Map<String, Any>).orEmpty()
                val checksum = MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
                podMetadata["annotations"] = annotations + ("observability.reaktor.build/config-sha256" to checksum)
                template["metadata"] = podMetadata; spec["template"] = template
                resource + ("spec" to spec)
            }
        }
        return maps + resources
    }

    private fun yaml(text: String): Map<String, Any> = Yaml().load(text)
}
