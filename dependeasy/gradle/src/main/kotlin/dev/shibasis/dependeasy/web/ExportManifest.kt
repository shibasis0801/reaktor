package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.process.nodeScript
import org.gradle.api.Project

internal fun Project.exportManifest(name: String, script: Any, output: Any,
                                   directory: () -> String, inputs: Array<out Any>): org.gradle.api.tasks.TaskProvider<NodeGenerator> =
    nodeScript(name, script) {
        val runtime = JavaScriptRuntime(project)
        val bundle = file(directory()).absolutePath
        val destination = file(output).absolutePath
        val root = rootDir.absolutePath
        val sources = files(*inputs)
        arguments.addAll(listOf("--root", root, "--bundle", bundle, "--output", destination))
        arguments.addAll(sources.elements.map { locations -> locations.sortedBy { it.asFile.path }.flatMap { listOf("--source", it.asFile.absolutePath) } })
        dependsOn(runtime.install)
        sourceFiles.from(rootProject.files("pnpm-workspace.yaml", "pnpm-lock.yaml"), runtime.sources, fileTree(directory()) {
                exclude("node_modules", "node_modules/**")
            }, inputs)
        outputFiles.from(file(output))
    }.also { task ->
        val model = dev.shibasis.dependeasy.workspace.BuildWorkspace.get(this)
        val workspace = PnpmWorkspace.get(this)
        model.connect(node(task), listOf(rootProject.node(workspace.install)))
        JavaScriptRuntime(this).output?.let { runtime -> model.consume(node(task), runtime) }
    }
