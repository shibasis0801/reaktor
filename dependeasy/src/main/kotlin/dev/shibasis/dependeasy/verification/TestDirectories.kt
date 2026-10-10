package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.files.deleteTreeSafely
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.io.Serializable
import java.nio.file.Files

fun Test.isolatedDirectory(property: String, directory: Provider<Directory>, create: List<String> = emptyList()) {
    val location = directory.get().asFile
    systemProperty(property, location.absolutePath)
    doFirst(ResetDirectory(location, project.layout.buildDirectory.get().asFile, create))
}

private class ResetDirectory(private val directory: File, private val boundary: File, private val create: List<String>) : Action<Task>, Serializable {
    override fun execute(task: Task) {
        require(!Files.isSymbolicLink(directory.toPath())) { "Test state must not be a symbolic link" }
        if (directory.exists()) Files.walk(directory.toPath()).use { paths ->
            paths.filter(Files::isSymbolicLink).forEach { link ->
                require(runCatching { link.toRealPath().startsWith(boundary.toPath().toRealPath()) }.getOrDefault(false)) {
                    "Test state link escapes build directory: $link"
                }
            }
        }
        directory.deleteTreeSafely(within = boundary)
        create.forEach { path ->
            val target = directory.resolve(path).toPath().toAbsolutePath().normalize()
            require(target.startsWith(directory.toPath().toAbsolutePath().normalize())) { "Test directory escapes state: $path" }
            Files.createDirectories(target)
        }
    }
}
