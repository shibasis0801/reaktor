package dev.shibasis.reaktor.conductor.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlinx.serialization.builtins.serializer

/** Local build outputs may disappear on clean; a running service needs its own restartable image. */
internal fun snapshotServiceCommand(command: List<String>, directory: Path): List<String> = withServiceImageLock(directory) { snapshotServiceImage(command, directory) }
private fun snapshotServiceImage(command: List<String>, directory: Path): List<String> {
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
    return command.toMutableList().apply { this[index + 1] = retained.joinToString(File.pathSeparator) }.also { saved ->
        privateDirectory(image.resolve("manifests"))
        val text = dev.shibasis.reaktor.conductor.ConductorJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(String.serializer()), saved)
        atomicWrite(image.resolve("manifests/${digest(text)}.json"), text)
    }
}

/** Only expired, unreferenced images. Keep the current JVM, installed supervisor and recent manifests. */
internal fun pruneServiceImages(directory: Path, olderThanDays: Int): Long = withServiceImageLock(directory) { pruneImages(directory, olderThanDays) }
private fun pruneImages(directory: Path, olderThanDays: Int): Long {
    require(olderThanDays in 7..3650)
    val image = directory.resolve("runtime")
    if (!Files.exists(image)) return 0
    val cutoff = System.currentTimeMillis() - olderThanDays * 86400000L
    val keep = mutableSetOf<String>()
    fun references(text: String) { Regex("[a-f0-9]{64}\\.jar").findAll(text).forEach { keep += it.value } }
    references(System.getProperty("java.class.path"))
    listOf("launch-agent.plist", "systemd.service", "run-service.ps1").forEach { name -> directory.resolve(name).takeIf(Files::exists)?.let { references(Files.readString(it)) } }
    val manifests = image.resolve("manifests")
    if (Files.exists(manifests)) Files.list(manifests).use { files -> files.filter { Files.getLastModifiedTime(it).toMillis() >= cutoff }.forEach { references(Files.readString(it)) } }
    return Files.list(image).use { paths -> paths.filter { it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.jar")) &&
        it.fileName.toString() !in keep && Files.getLastModifiedTime(it).toMillis() < cutoff }.toList() }.sumOf { file ->
        val bytes = Files.size(file); Files.delete(file); bytes
    }
}

private val imageMutex = Any()
private fun <T> withServiceImageLock(directory: Path, action: () -> T): T = synchronized(imageMutex) {
    privateDirectory(directory)
    java.nio.channels.FileChannel.open(directory.resolve("runtime-image.lock"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
        channel.lock().use { action() }
    }
}
