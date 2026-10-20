package dev.shibasis.dependeasy.benchmark

import dev.shibasis.dependeasy.toolchain.NativeBuildTools
import org.gradle.api.Project

internal class ProfilerTools(project: Project) {
    private val tools = NativeBuildTools.get(project)
    val library = tools.hostFile("ASPROF_LIB")
    val jar = tools.hostFile("ASPROF_JAR")
}
