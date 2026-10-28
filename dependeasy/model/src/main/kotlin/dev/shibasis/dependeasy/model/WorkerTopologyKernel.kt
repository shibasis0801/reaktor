package dev.shibasis.dependeasy.model

import kotlinx.serialization.json.*
import java.io.File

internal fun workerTopology(input: FileSet): Map<String, String> {
    if (input.paths.isEmpty()) return emptyMap()
    val fleet = BuildModelReader.json.decodeFromString<WorkerFleetContract>(File(input.paths.single()).readText())
    require(fleet.schema == 1) { "Unsupported worker fleet schema: ${fleet.schema}" }
    return mapOf("Topology" to WorkerTopologyKernel.project(fleet).toString())
}

object WorkerTopologyKernel {
    fun project(fleet: WorkerFleetContract): JsonObject = buildJsonObject {
        put("schema", 1)
        putJsonArray("deployments") {
            fleet.workers.sortedBy { it.name }.forEach { worker ->
                worker.deployments.sortedBy { it.environment.name }.forEach { deployment ->
                    val settings = worker.settings[deployment.environment.name] ?: JsonObject(emptyMap())
                    add(buildJsonObject {
                        put("worker", worker.name)
                        put("environment", deployment.environment.name)
                        putJsonArray("bindings") {
                            fun binding(value: JsonObject, kind: String, nameKey: String = "binding", targetKey: String? = null) {
                                val name = value[nameKey]?.jsonPrimitive?.contentOrNull ?: return
                                add(buildJsonObject {
                                    put("name", name)
                                    put("kind", kind)
                                    if (kind == "durable_objects") value["script_name"]?.jsonPrimitive?.contentOrNull?.let { put("ownerScript", it) }
                                    targetKey?.let { key -> value[key]?.jsonPrimitive?.contentOrNull?.let { put("target", it) } }
                                })
                            }
                            listOf("hyperdrive", "d1_databases", "kv_namespaces", "r2_buckets", "services", "vpc_services", "workflows", "vectorize", "analytics_engine_datasets", "dispatch_namespaces", "pipelines").forEach { kind ->
                                settings[kind]?.jsonArray.orEmpty().forEach { value ->
                                    binding(value.jsonObject, kind, targetKey = when (kind) {
                                        "services" -> "service"
                                        "workflows" -> "class_name"
                                        "vpc_services" -> "service_id"
                                        else -> null
                                    })
                                }
                            }
                            (settings["ai"] as? JsonObject)?.let { binding(it, "ai") }
                            (settings["assets"] as? JsonObject)?.let { binding(it, "assets") }
                              (settings["browser"] as? JsonObject)?.let { binding(it, "browser") }
                            settings["durable_objects"]?.jsonObject?.get("bindings")?.jsonArray.orEmpty().forEach {
                                binding(it.jsonObject, "durable_objects", "name", "class_name")
                            }
                            settings["queues"]?.jsonObject?.get("producers")?.jsonArray.orEmpty().forEach {
                                binding(it.jsonObject, "queue", targetKey = "queue")
                            }
                        }
                        put("crons", settings["triggers"]?.jsonObject?.get("crons") ?: JsonArray(emptyList()))
                        putJsonArray("queues") {
                            settings["queues"]?.jsonObject?.get("consumers")?.jsonArray.orEmpty().forEach {
                                it.jsonObject["queue"]?.let(::add)
                            }
                        }
                        putJsonArray("routes") {
                            settings["routes"]?.jsonArray.orEmpty().forEach {
                                (it as? JsonObject)?.get("pattern")?.let(::add)
                            }
                        }
                    })
                }
            }
        }
    }
}
