package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.CommandTask
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal class JavaScriptCommandKernel(
    private val project: Project,
    private val name: String,
    private val directory: java.io.File,
    private val component: JavaScriptComponent,
) {
    private val workspace = PnpmWorkspace.get(project)
    private val runtime = JavaScriptRuntime(project)
    private val install = workspace.install
    fun command(stage: String, output: String?, vararg arguments: String, fileOutput: String? = null): TaskProvider<CommandTask> {
        output?.let { component.generatedOutputs.add("$it/**") }
        fileOutput?.let(component.generatedOutputs::add)
        val taskName = name + stage.split(Regex("[^A-Za-z0-9]+"))
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        return project.tasks.register<CommandTask>(taskName) {
            group = "dependeasy"
            backend.set(when (stage) { "bundle" -> "vite"; "types" -> "typescript"; "bindings" -> "karakum"; else -> "pnpm" })
            dependsOn(install, runtime.install, component.prerequisites)
            workingDirectory.set(directory)
            executable.set(workspace.tools.pnpm.map { it.asFile.absolutePath })
            environment.put("PATH", workspace.tools.path)
            this.arguments.set(arguments.toList())
            toolVersion.set("pnpm ${ToolchainVersions.Pnpm} / node ${ToolchainVersions.Node}")
            sourceFiles.from(project.fileTree(directory) {
                if (directory == project.projectDir && project.file("src").isDirectory)
                    include("package.json", "*config.*", "src/**/typescript/**", "ts/**", "js/**", "scripts/**", "test/**")
                exclude("**/node_modules/**", "**/dist/**", "**/build/**", "**/.git/**", "**/.gradle/**", "**/.kotlin/**", "**/*.tsbuildinfo", ".wrangler/**", ".vite/**", "coverage/**", ".cache-*/**", "logs/**", "*.log", "wrangler.dependeasy-*.json", "$output/**")
                if (directory == project.projectDir) exclude("ts/export/**")
                exclude(component.generatedOutputs)
            })
            sourceFiles.from(workspace.directory.resolve("pnpm-lock.yaml"), component.sourceInputs)
            val linked = linkedPackageInputs(listOf(directory.resolve("package.json")))
            sourceFiles.from(linked)
            linked.filter { it.name == "package.json" && it.parentFile != directory }.forEach { manifest ->
                sourceFiles.from(project.fileTree(manifest.parentFile) {
                    exclude("**/node_modules/**", "**/dist/**", "**/build/**", "**/.git/**", "**/.gradle/**", "**/.kotlin/**", "**/*.tsbuildinfo")
                })
            }
            if (fileOutput != null) outputFiles.from(directory.resolve(fileOutput))
            else if (output != null) outputDirectories.from(directory.resolve(output))
            else receiptFile.set(project.layout.buildDirectory.file("dependeasy/checks/$taskName.properties"))
        }
    }
}
