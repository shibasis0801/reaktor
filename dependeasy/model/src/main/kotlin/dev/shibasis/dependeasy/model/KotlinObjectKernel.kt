package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.files.clearChildrenSafely
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

object KotlinObjectKernel {
    fun generate(spec: KernelSpec.GeneratedObject, values: Map<String, String>, strings: Map<String, String>,
        booleans: Map<String, Boolean>, texts: Map<String, String>, output: File) {
        val endpoints = workerEndpoints(spec.workerFleet)
        require((values.keys + strings.keys + booleans.keys + texts.keys).intersect(endpoints.keys).isEmpty()) {
            "Generated fields conflict with Worker endpoints"
        }
        val fields = values.map { (name, value) -> "    val $name: String = ${literal(value)}" } +
            (strings + endpoints).map { (name, value) -> "    const val $name: String = ${literal(value)}" } +
            booleans.map { (name, value) -> "    const val $name: Boolean = $value" } +
            texts.map { (name, value) ->
                val chunks = value.chunked(16_000).ifEmpty { listOf("") }.joinToString(",\n        ", transform = ::literal)
                "    val $name: String = listOf(\n        $chunks,\n    ).joinToString(\"\")"
            }
        output.clearChildrenSafely()
        val destination = output.resolve("${spec.namespace.replace('.', '/')}/${spec.objectName}.kt")
        destination.parentFile.mkdirs()
        destination.writeText("package ${spec.namespace}\n\ninternal object ${spec.objectName} {\n" +
            fields.sorted().joinToString("\n") + "\n}\n")
    }

    private fun workerEndpoints(input: FileSet): Map<String, String> {
        if (input.paths.isEmpty()) return emptyMap()
        val fleet = BuildModelReader.json.decodeFromString<WorkerFleetContract>(File(input.paths.single()).readText())
        require(fleet.schema == 1) { "Unsupported worker fleet schema: ${fleet.schema}" }
        val endpoints = linkedMapOf<String, String>()
        fleet.workers.forEach { worker ->
            worker.deployments.forEach { deployment ->
                val environment = deployment.environment.name
                val domains = worker.settings[environment]?.get("routes")?.jsonArray.orEmpty().mapNotNull { route ->
                    (route as? JsonObject)?.takeIf { it["custom_domain"]?.jsonPrimitive?.booleanOrNull == true }
                        ?.get("pattern")?.jsonPrimitive?.content
                }.distinct()
                require(domains.size <= 1 || deployment.publicEndpoint != null) {
                    "Worker ${worker.name} has multiple public domains in $environment; declare its publicEndpoint"
                }
                val endpoint = deployment.publicEndpoint ?: domains.singleOrNull()?.let { "https://$it" }
                if (endpoint != null) {
                    val uri = java.net.URI(endpoint)
                    require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                        "Invalid public Worker endpoint: ${worker.name}/$environment"
                    }
                    val field = environment.replaceFirstChar(Char::uppercaseChar) + worker.name.replaceFirstChar(Char::uppercaseChar)
                    require(field.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid Worker endpoint identifier: $field" }
                    require(endpoints.putIfAbsent(field, endpoint) == null) { "Duplicate Worker endpoint identifier: $field" }
                }
            }
        }
        return endpoints
    }

    private fun literal(value: String): String = Json.encodeToString(value).replace("$", "\\$")
}
