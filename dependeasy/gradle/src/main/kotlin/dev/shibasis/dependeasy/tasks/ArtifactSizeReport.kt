package dev.shibasis.dependeasy.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.security.MessageDigest

@CacheableTask
abstract class ArtifactSizeReport : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val artifacts: ConfigurableFileCollection
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction fun measure() {
        val rows = artifacts.files.filter { it.isFile }.map { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            "${file.name},${file.length()},$hash"
        }.sorted()
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText((listOf("artifact,bytes,sha256") + rows).joinToString("\n") + "\n")
        }
    }
}
