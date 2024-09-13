package dev.shibasis.dependeasy.utils

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


// clone a git repo if it does not exist
fun gitDependency(repoURL: String, downloadRoot: File) {
    val repoName = repoURL.split(".git").first().split("/").last()
    val subdirectory = File(downloadRoot, repoName)

    val path = Paths.get(subdirectory.absolutePath)
    if (!Files.exists(path)) {
        val cloneCommand = "git clone --depth 1 --single-branch  $repoURL $path"
        println("Executing: $cloneCommand")
        cloneCommand.execute()
    } else {
        println("Repository already exists at ${path.toAbsolutePath()}")
    }
}

fun Settings.includeWithPath(name: String, path: String? = null) {
    val newName = ":$name"
    settings.include(newName)
    if (path != null) {
        project(newName).projectDir = File(path)
    }
}
