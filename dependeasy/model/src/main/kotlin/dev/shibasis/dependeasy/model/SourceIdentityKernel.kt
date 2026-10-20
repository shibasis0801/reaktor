package dev.shibasis.dependeasy.model

import java.io.File
import java.security.MessageDigest
import java.time.Instant

object SourceIdentityKernel {
    fun generate(spec: KernelSpec.SourceIdentity, files: Map<String, Set<File>>, revisions: Map<String, String>, output: File) {
        val entries = spec.sources.flatMap { group ->
            val directory = File(group.directory).canonicalFile
            files.getValue(group.name).filter(File::isFile).map { file ->
                require(file.canonicalFile.toPath().startsWith(directory.toPath())) { "Identity source escapes ${group.name}: $file" }
                "${group.name}/${file.canonicalFile.relativeTo(directory).invariantSeparatorsPath}" to file
            }
        }.sortedBy { it.first }
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { (path, file) ->
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var size = input.read(buffer)
                while (size >= 0) { digest.update(buffer, 0, size); size = input.read(buffer) }
            }
        }
        val values = revisions.toMutableMap()
        values["sourceDigest"] = digest.digest().joinToString("") { "%02x".format(it) }
        if (spec.recordTime) values["builtAt"] = Instant.now().toString()
        val destination = output.resolve(spec.fileName)
        require(destination.toPath().normalize().startsWith(output.toPath().normalize())) { "Identity output escapes its directory" }
        destination.parentFile.mkdirs()
        destination.writeText(values.toSortedMap().entries.joinToString("\n", postfix = "\n") { (key, value) ->
            "${escape(key)}=${escape(value)}"
        }, Charsets.ISO_8859_1)
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n")
        .replace("\r", "\\r").replace("\t", "\\t").replace(" ", "\\ ").replace("=", "\\=")
        .replace(":", "\\:").replace("#", "\\#").replace("!", "\\!")
}
