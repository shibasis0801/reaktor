package dev.shibasis.dependeasy.benchmark

import org.gradle.api.Project
import java.io.File

internal class ProfilerTools(private val project: Project) {
    private fun existing(environment: String, paths: List<String>) =
        project.providers.environmentVariable(environment).map { path -> listOf(path) }
            .orElse(paths).map { candidates -> candidates.firstOrNull { File(it).isFile } ?: "" }
    val library = existing("ASPROF_LIB", listOf("/opt/homebrew/lib/libasyncProfiler.dylib",
        "/usr/local/lib/libasyncProfiler.dylib", "${System.getProperty("user.home")}/.local/lib/libasyncProfiler.dylib"))
    val jar = existing("ASPROF_JAR", listOf("/opt/homebrew/opt/async-profiler/libexec/async-profiler.jar",
        "/usr/local/opt/async-profiler/libexec/async-profiler.jar"))
}
