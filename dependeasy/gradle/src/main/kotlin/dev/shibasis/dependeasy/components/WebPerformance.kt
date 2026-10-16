package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.nodeScript
import dev.shibasis.dependeasy.tasks.ArtifactSizeReport
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

internal fun Project.webPerformance(name: String, spec: WebPerformance, bundle: NodeHandle): NodeHandle {
    val measurements = rootProject.layout.projectDirectory.file(spec.measurements)
    val measured = tasks.register<ArtifactSizeReport>("${name}Measurements") {
        dependsOn(requireNotNull(bundle.task))
        artifacts.from(fileTree(spec.directory))
        baseDirectory.set(layout.projectDirectory.dir(spec.directory))
        metrics.set(spec.metrics)
        budgets.set(spec.budgets)
        report.set(measurements)
    }
    val workspace = BuildWorkspace.get(this)
    workspace.reference(this, measured, null, family = "web")
    val checked = nodeScript(name, rootProject.file(spec.adapter)) {
        executionEffect.set(Effect.Verify)
        dependsOn(measured)
        sourceFiles.from(measured.flatMap { it.report })
        arguments.add(measurements.asFile.absolutePath)
        val result = rootProject.layout.projectDirectory.file(spec.report)
        arguments.add(result.asFile.absolutePath)
        outputFiles.from(result)
    }
    workspace.reference(this, checked, null, family = "web")
    return node(checked)
}
