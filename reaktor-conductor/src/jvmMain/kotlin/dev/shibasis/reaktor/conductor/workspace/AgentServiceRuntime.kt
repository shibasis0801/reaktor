package dev.shibasis.reaktor.conductor.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Local build outputs may disappear on clean; a running service needs its own restartable image. */
internal fun snapshotServiceCommand(command: List<String>, directory: Path): List<String> {
    val index = command.indexOf("-cp")
    if (index < 0) return command
    val image = directory.resolve("runtime")
    privateDirectory(image)
    val entries = command[index + 1].split(File.pathSeparator).map { File(it).canonicalFile }.distinct()
        .filter { it.exists() || it.extension == "jar" }
    val retained = entries.map { entry ->
        require(entry.exists()) { "Service classpath is missing: $entry" }
        val temporary = Files.createTempFile(image, ".image-", ".jar")
        try {
            if (entry.isDirectory) JarOutputStream(Files.newOutputStream(temporary)).use { jar ->
                entry.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                    jar.putNextEntry(JarEntry(file.relativeTo(entry).invariantSeparatorsPath).apply { time = 0 })
                    file.inputStream().use { it.copyTo(jar) }
                    jar.closeEntry()
                }
            } else Files.copy(entry.toPath(), temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(temporary).use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            val target = image.resolve(hash.digest().joinToString("") { "%02x".format(it) } + ".jar")
            if (!Files.exists(target)) Files.move(temporary, target)
            target.toString()
        } finally { Files.deleteIfExists(temporary) }
    }
    return command.toMutableList().apply { this[index + 1] = retained.joinToString(File.pathSeparator) }
}
