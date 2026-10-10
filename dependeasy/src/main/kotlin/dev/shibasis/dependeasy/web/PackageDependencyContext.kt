package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.files.deleteTreeSafely
import org.gradle.api.Action
import org.gradle.api.Task
import java.io.File
import java.io.Serializable
import java.nio.file.Files

internal class PackageDependencyContext(private val source: File, private val exported: File) : Action<Task>, Serializable {
    private val link get() = exported.resolve("node_modules").toPath()
    fun matches() = Files.isSymbolicLink(link) && link.parent.resolve(Files.readSymbolicLink(link)).normalize() == source.toPath().normalize()

    override fun execute(task: Task) {
        if (!source.isDirectory || matches()) return
        exported.mkdirs()
        if (Files.isSymbolicLink(link)) Files.delete(link)
        else if (Files.exists(link)) link.toFile().deleteTreeSafely(within = exported)
        Files.createSymbolicLink(link, link.parent.relativize(source.toPath()))
    }
}
