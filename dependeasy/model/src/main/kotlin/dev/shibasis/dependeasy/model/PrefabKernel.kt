package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.files.clearChildrenSafely
import java.io.File
import java.util.zip.ZipFile

object PrefabKernel {
    fun extract(spec: KernelSpec.Prefab, archives: Collection<File>) {
        require(spec.output.paths.size == 1)
        val aar = archives.single()
        if (spec.checksum.isNotEmpty()) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            aar.inputStream().use { input -> val buffer = ByteArray(65536); while(true) { val count = input.read(buffer); if(count < 0) break; digest.update(buffer, 0, count) } }
            require(digest.digest().joinToString("") { "%02x".format(it) } == spec.checksum) { "Native AAR checksum mismatch" }
        }
        val output = File(spec.output.paths.single())
        output.clearChildrenSafely()
        output.mkdirs()
        val prefix = "prefab/modules/${spec.module}/"
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
        require(count > 0) { "AAR $aar has no prefab module '${spec.module}'" }
    }
}
