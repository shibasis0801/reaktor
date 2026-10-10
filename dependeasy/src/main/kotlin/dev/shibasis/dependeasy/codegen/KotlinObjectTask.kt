package dev.shibasis.dependeasy.codegen

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import java.io.File
import org.gradle.work.DisableCachingByDefault

/** Generated values have declared inputs and a producer-backed source directory. */
@DisableCachingByDefault(because = "Generated text can contain development credentials; use local incremental execution")
abstract class KotlinObjectTask : DefaultTask() {
    @get:Input abstract val packageName: Property<String>
    @get:Input abstract val objectName: Property<String>
    @get:Input abstract val strings: MapProperty<String, String>
    @get:Input abstract val booleans: MapProperty<String, Boolean>
    @get:Input abstract val texts: MapProperty<String, String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    init {
        strings.convention(emptyMap())
        booleans.convention(emptyMap())
        texts.convention(emptyMap())
    }

    fun string(name: String, value: Provider<String>) { strings.put(name, value) }
    fun boolean(name: String, value: Provider<Boolean>) { booleans.put(name, value) }

    fun text(name: String, file: File, enabled: Provider<Boolean>) {
        val contents = project.providers.provider { file.takeIf(File::isFile)?.readText().orEmpty() }
        val empty = project.providers.provider { "" }
        texts.put(name, enabled.flatMap { if (it) contents else empty })
    }

    @TaskAction fun generate() {
        val fields = strings.get().map { (name, value) -> "    const val $name: String = ${literal(value)}" } +
            booleans.get().map { (name, value) -> "    const val $name: Boolean = $value" } +
            texts.get().map { (name, value) ->
                val chunks = value.chunked(16_000).ifEmpty { listOf("") }.joinToString(",\n        ", transform = ::literal)
                "    val $name: String = listOf(\n        $chunks,\n    ).joinToString(\"\")"
            }
        val destination = outputDirectory.get().asFile.resolve("${packageName.get().replace('.', '/')}/${objectName.get()}.kt")
        destination.parentFile.mkdirs()
        destination.writeText("package ${packageName.get()}\n\ninternal object ${objectName.get()} {\n" +
            fields.sorted().joinToString("\n") + "\n}\n")
    }

    private fun literal(value: String): String = Json.encodeToString(value).replace("$", "\\$")
}
