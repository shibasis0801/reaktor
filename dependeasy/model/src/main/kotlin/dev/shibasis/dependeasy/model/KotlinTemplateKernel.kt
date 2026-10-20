package dev.shibasis.dependeasy.model

import java.util.Base64

object KotlinTemplateKernel {
    fun render(spec: KernelSpec.KotlinTemplate, template: String, assets: Map<String, ByteArray>): String {
        require(spec.template.paths.size == 1 && spec.output.paths.size == 1)
        require(spec.assets.values.all { it.paths.size == 1 } && assets.keys == spec.assets.keys)
        var source = template
        assets.toSortedMap().forEach { (token, bytes) ->
            val placeholder = "{{$token}}"
            require(placeholder in source) { "Template has no placeholder for $token" }
            val chunks = Base64.getEncoder().encodeToString(bytes).chunked(16_000).ifEmpty { listOf("") }
            val expression = chunks.joinToString(",\n") { "\"$it\"" }.let { "listOf($it).joinToString(\"\")" }
            source = source.replace(placeholder, expression)
        }
        require(!Regex("\\{\\{[A-Za-z][A-Za-z0-9_]*}}").containsMatchIn(source)) { "Template has unbound placeholders" }
        return source
    }
}
