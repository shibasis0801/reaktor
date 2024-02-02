package dev.shibasis.dependeasy.utils

import org.gradle.api.initialization.Settings
import org.gradle.api.plugins.PluginAware
import org.gradle.kotlin.dsl.SettingsScriptApi
import org.gradle.kotlin.dsl.support.delegates.SettingsDelegate
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
fun gitDependency(path: File, repoURL: String) {
    val localPath = Paths.get(path.absolutePath)
    if (!Files.exists(localPath)) {
        val cloneCommand = "git clone $repoURL ${localPath.toAbsolutePath()}"
        println("Executing: $cloneCommand")
        cloneCommand.execute()
    } else {
        println("Repository already exists at ${localPath.toAbsolutePath()}")
    }
}

fun Settings.includeWithPath(name: String, path: String? = null) {
    val newName = ":$name"
    settings.include(newName)
    if (path != null) {
        project(newName).projectDir = File(path)
    }
}
