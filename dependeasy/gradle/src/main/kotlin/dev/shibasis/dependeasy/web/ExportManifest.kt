package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.nodeScript
import org.gradle.api.Project

internal fun Project.exportManifest(name: String, script: Any, output: Any,
                                   directory: () -> String, inputs: Array<out Any>) =
    nodeScript(name, script) {
        val runtime = JavaScriptRuntime(project)
        dependsOn(runtime.install)
        sourceFiles.from(rootProject.files("pnpm-workspace.yaml", "pnpm-lock.yaml",
            runtime.file("api/workspace.ts")), runtime.sources, fileTree(directory()) {
                exclude("node_modules", "node_modules/**")
            }, inputs)
        outputFiles.from(file(output))
    }
