package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.KernelRef
import dev.shibasis.dependeasy.model.SourceSite
import org.gradle.api.Project
import org.gradle.api.Task

internal fun Project.declarationSite(): SourceSite? = Throwable().stackTrace
    .firstOrNull { it.fileName?.endsWith(".gradle.kts") == true && it.lineNumber > 0 }
    ?.let { SourceSite(rootProject.name, buildFile.relativeTo(rootDir).invariantSeparatorsPath, it.lineNumber) }

internal fun kernelRef(task: Task): KernelRef {
    val type = generateSequence<Class<*>>(task.javaClass) { it.superclass }
        .firstOrNull { !it.name.endsWith("_Decorated") } ?: task.javaClass
    return kernelRef(task.project, type)
}

internal fun kernelRef(project: Project, type: Class<*>): KernelRef {
    val sourceOwner = (listOf(project.rootDir) + project.gradle.includedBuilds.map { it.projectDir })
        .firstOrNull { it.resolve("dependeasy/gradle/src/main/kotlin").isDirectory }
    val source = sourceOwner?.resolve("dependeasy/gradle/src/main/kotlin/" + type.name.replace('.', '/') + ".kt")
    val line = source?.takeIf { it.isFile }?.readLines()?.indexOfFirst {
        Regex("\\bclass\\s+" + Regex.escape(type.simpleName) + "\\b").containsMatchIn(it)
    }?.takeIf { it >= 0 }?.plus(1)
    return KernelRef(type.name, if (sourceOwner != null && source != null && line != null) SourceSite(sourceOwner.name,
        source.relativeTo(sourceOwner).invariantSeparatorsPath, line) else null)
}
