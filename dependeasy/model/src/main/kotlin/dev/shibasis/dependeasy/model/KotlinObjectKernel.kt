package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.files.clearChildrenSafely
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object KotlinObjectKernel {
    fun generate(spec: KernelSpec.GeneratedObject, values: Map<String, String>, strings: Map<String, String>,
        booleans: Map<String, Boolean>, texts: Map<String, String>, output: File) {
        val fields = values.map { (name, value) -> "    val $name: String = ${literal(value)}" } +
            strings.map { (name, value) -> "    const val $name: String = ${literal(value)}" } +
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

    private fun literal(value: String): String = Json.encodeToString(value).replace("$", "\\$")
}
