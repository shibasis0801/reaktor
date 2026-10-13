package dev.shibasis.dependeasy.web

import org.gradle.api.Project
import java.io.File

/** One owner for adapter locations, installation and kernel input tracking. */
internal class JavaScriptRuntime(private val project: Project) {
    val directory: File = project.gradle.includedBuilds.find { it.name == "dependeasy" }
        ?.projectDir?.resolve("javascript")
        ?: project.rootDir.resolve("../reaktor/dependeasy/javascript")
    fun file(path: String) = directory.resolve(path)
    val sources = project.fileTree(directory) { include("api/**", "kernel/**", "cli/**", "package.json") }
    val install: Any
        get() = if (!directory.resolve("package.json").isFile ||
            project.rootDir.canonicalFile == directory.parentFile.parentFile.canonicalFile)
            PnpmWorkspace.get(project).install
        else project.gradle.includedBuild("reaktor").task(":pnpmInstall")
}
