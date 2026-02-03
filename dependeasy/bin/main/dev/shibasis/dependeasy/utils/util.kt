package dev.shibasis.dependeasy.utils

import org.gradle.api.GradleException
import org.gradle.api.initialization.Settings
import java.nio.file.Files
import java.nio.file.Paths
import java.io.File

// Extension function to execute shell commands
fun String.execute() {
    val process = Runtime.getRuntime().exec(this)
    process.inputStream.reader(Charsets.UTF_8).use { reader ->
        println(reader.readText())
    }
}


fun File.gitDependency(repoURL: String) {
    val downloadRoot = this
    val repoName = repoURL.split(".git").first().split("/").last()
    val subdirectory = File(downloadRoot, repoName)

    val path = Paths.get(subdirectory.absolutePath)
    if (!Files.exists(path)) {
        val cloneCommand = listOf("git", "clone", "--depth", "1", "--single-branch", repoURL, path.toString())
        println("Executing: ${cloneCommand.joinToString(" ")}")

        // Execute the clone command and wait for it to complete
        val process = ProcessBuilder(cloneCommand)
            .directory(downloadRoot)
            .inheritIO() // This ensures output is displayed in the console
            .start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("Git clone failed with exit code $exitCode")
        }
    } else {
        println("Repository already exists at ${path.toAbsolutePath()}")
    }
}


fun Settings.includeWithPath(name: String, path: String? = null) {
    val newName = ":$name"
    include(newName)
    if (path != null) {
        project(newName).projectDir = File(path)
    }
}
