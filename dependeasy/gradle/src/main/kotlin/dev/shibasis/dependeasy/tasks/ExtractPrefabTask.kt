package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.files.clearChildrenSafely
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
    @get:Input abstract val expectedChecksum: Property<String>
    @get:Input abstract val moduleName: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    init { expectedChecksum.convention("") }

    @TaskAction fun extract() {
        val aar = archiveFiles.singleFile
        if (expectedChecksum.get().isNotEmpty()) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            aar.inputStream().use { input -> val buffer = ByteArray(65536); while(true) { val count = input.read(buffer); if(count < 0) break; digest.update(buffer, 0, count) } }
            require(digest.digest().joinToString("") { "%02x".format(it) } == expectedChecksum.get()) { "Native AAR checksum mismatch" }
        }
        val output = outputDirectory.get().asFile
        output.clearChildrenSafely()
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
