package dev.shibasis.dependeasy.codegen

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.util.Base64

@CacheableTask
abstract class KotlinTemplateTask : DefaultTask() {
    @get:Internal abstract val root: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val templateFile: RegularFileProperty
    @get:Input abstract val assetPaths: MapProperty<String, String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val assetFiles: ConfigurableFileCollection
    @get:Input abstract val fileName: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        var source = templateFile.get().asFile.readText()
        assetPaths.get().toSortedMap().forEach { (token, path) ->
            val placeholder = "{{$token}}"
            require(placeholder in source) { "Template has no placeholder for $token" }
            val encoded = Base64.getEncoder().encodeToString(root.file(path).get().asFile.readBytes())
            val chunks = encoded.chunked(16_000).ifEmpty { listOf("") }
            val expression = chunks.joinToString(",\n") { "\"$it\"" }.let { "listOf($it).joinToString(\"\")" }
            source = source.replace(placeholder, expression)
        }
        require(!Regex("\\{\\{[A-Za-z][A-Za-z0-9_]*}}").containsMatchIn(source)) { "Template has unbound placeholders" }
        outputDirectory.file(fileName.get()).get().asFile.apply { parentFile.mkdirs(); writeText(source) }
    }
}
