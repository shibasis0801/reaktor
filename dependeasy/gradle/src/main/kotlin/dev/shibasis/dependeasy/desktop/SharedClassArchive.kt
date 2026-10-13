package dev.shibasis.dependeasy.desktop

import java.io.File
import java.security.MessageDigest

/** Preserve ordinary JVM arguments and partition archives by access flags and jar content. */
fun sharedClassArchive(arguments: List<String>, home: String, name: String, jars: Collection<File> = emptyList()): List<String> {
    val base = arguments.filterNot {
        it.startsWith("-XX:SharedArchiveFile=") || it == "-XX:+AutoCreateSharedArchive" || it.startsWith("-Xlog:cds=")
    }
    val digest = MessageDigest.getInstance("SHA-256")
    base.filter { it.startsWith("--add-") || it.startsWith("--enable-native-access") }
        .sorted().forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0) }
    jars.filter { it.extension == "jar" }.sortedBy { it.name }.forEach { jar ->
        digest.update(jar.name.toByteArray(Charsets.UTF_8)); digest.update(0)
        jar.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            generateSequence { input.read(buffer).takeIf { it >= 0 } }.forEach { digest.update(buffer, 0, it) }
        }
    }
    val key = digest.digest().joinToString("") { "%02x".format(it) }.take(12)
    return base + listOf("-XX:+AutoCreateSharedArchive", "-XX:SharedArchiveFile=$home/$name-$key.jsa", "-Xlog:cds=error")
}
