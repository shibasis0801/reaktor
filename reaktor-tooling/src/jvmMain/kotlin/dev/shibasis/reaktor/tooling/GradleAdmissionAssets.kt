package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

internal data class GradleAdmissionCommand(val adapter: File, val options: List<String> = emptyList())

internal object GradleAdmissionAssets {
    private val names = listOf("gradle-lease", "remote_build.py", "remote_retention.py", "cluster_locations.py")
    private val privateDirectory = PosixFilePermissions.fromString("rwx------")
    private val privateFile = PosixFilePermissions.fromString("rw-------")

    fun resolve(root: File, environment: Map<String, String>): GradleAdmissionCommand {
        val home = File(environment["HOME"] ?: System.getProperty("user.home"))
        val installed = (listOf(root.resolve("reaktor-cluster/tooling/gradle-lease"),
            root.resolve("../reaktor/reaktor-cluster/tooling/gradle-lease")) +
            (environment["PATH"] ?: System.getenv("PATH").orEmpty()).split(File.pathSeparator)
                .filter(String::isNotBlank).map { File(it, "gradle-lease") } +
            listOf(home.resolve("dev/reaktor/reaktor-cluster/tooling/gradle-lease")))
            .firstOrNull { it.isFile && it.canExecute() }?.canonicalFile
        return if (installed != null) GradleAdmissionCommand(installed)
        else GradleAdmissionCommand(materialize(root), listOf("--state-workspace", home.resolve("dev").canonicalPath))
    }

    internal fun materialize(root: File): File {
        val assets = names.associateWith { name ->
            requireNotNull(javaClass.getResourceAsStream("/dev/shibasis/reaktor/tooling/gradle-admission/$name")) {
                "The packaged Gradle admission tools are unavailable"
            }.use { it.readBytes() }
        }
        val digest = MessageDigest.getInstance("SHA-256").apply {
            assets.forEach { (name, bytes) -> update(name.toByteArray()); update(0.toByte()); update(bytes) }
        }.digest().joinToString("") { "%02x".format(it) }
        val parent = root.canonicalFile.resolve(".gradle/reaktor/cluster-admission").toPath()
        require(parent.toFile().canonicalFile.toPath() == parent) { "Gradle admission tools escape their project" }
        Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(privateDirectory))
        val target = parent.resolve(digest)
        if (!Files.exists(target, NOFOLLOW_LINKS)) {
            val staged = Files.createTempDirectory(parent, ".assets-", PosixFilePermissions.asFileAttribute(privateDirectory))
            try {
                assets.forEach { (name, bytes) ->
                    val file = staged.resolve(name)
                    Files.createFile(file, PosixFilePermissions.asFileAttribute(if (name == "gradle-lease") privateDirectory else privateFile))
                    Files.write(file, bytes)
                }
                try {
                    Files.move(staged, target, ATOMIC_MOVE)
                } catch (failure: FileSystemException) {
                    if (!Files.exists(target, NOFOLLOW_LINKS)) throw failure
                }
            } finally {
                staged.toFile().deleteTreeSafely(within = parent.toFile())
            }
        }
        require(Files.isDirectory(target, NOFOLLOW_LINKS) && target.toFile().canonicalFile.toPath() == target) {
            "Gradle admission tools escape their project"
        }
        assets.forEach { (name, bytes) ->
            val file = target.resolve(name)
            require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.readAllBytes(file).contentEquals(bytes)) {
                "The packaged Gradle admission tools changed after installation"
            }
        }
        return target.resolve("gradle-lease").toFile().also { require(it.canExecute()) { "Gradle admission tools are not executable" } }
    }
}
