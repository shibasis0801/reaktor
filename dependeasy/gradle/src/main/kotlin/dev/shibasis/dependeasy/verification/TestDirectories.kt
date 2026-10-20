package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.model.TestEnvironmentKernel
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.io.Serializable

fun Test.isolatedDirectory(property: String, directory: Provider<Directory>, create: List<String> = emptyList()) {
    val location = directory.get().asFile
    systemProperty(property, location.absolutePath)
    doFirst(ResetDirectory(location, project.layout.buildDirectory.get().asFile, create))
}

private class ResetDirectory(private val directory: File, private val boundary: File, private val create: List<String>) : Action<Task>, Serializable {
    override fun execute(task: Task) = TestEnvironmentKernel.prepare(directory, boundary, create)
}
