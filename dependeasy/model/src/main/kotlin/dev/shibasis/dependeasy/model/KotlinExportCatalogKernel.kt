package dev.shibasis.dependeasy.model

import kotlinx.serialization.json.*

object KotlinExportCatalogKernel {
    fun render(spec: KernelSpec.KotlinExportCatalog): String {
        require(spec.output.paths.size == 1)
        val metadata = buildJsonObject {
            put("schemaVersion", 1)
            putJsonArray("directories") { spec.directories.paths.distinct().sorted().forEach { add(it) } }
        }
        return Json.encodeToString(metadata) + "\n"
    }
}
