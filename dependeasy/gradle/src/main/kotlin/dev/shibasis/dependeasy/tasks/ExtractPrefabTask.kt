package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.files.deleteTreeSafely
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.util.zip.ZipFile

@CacheableTask
abstract class ExtractPrefabTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val archiveFiles: ConfigurableFileCollection
    @get:Input abstract val moduleName: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun extract() {
        val aar = archiveFiles.singleFile
        val output = outputDirectory.get().asFile
        output.deleteTreeSafely(within = output.parentFile)
        output.mkdirs()
        val prefix = "prefab/modules/${moduleName.get()}/"
        var count = 0
        ZipFile(aar).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(prefix) }.forEach { entry ->
                val target = output.resolve(entry.name.removePrefix(prefix)).canonicalFile
                require(target.toPath().startsWith(output.canonicalFile.toPath())) { "Invalid prefab archive entry: ${entry.name}" }
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
                count++
            }
        }
        require(count > 0) { "AAR $aar has no prefab module '${moduleName.get()}'" }
    }
}
