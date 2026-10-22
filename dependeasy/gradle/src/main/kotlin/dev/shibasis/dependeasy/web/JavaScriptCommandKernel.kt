package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import dev.shibasis.dependeasy.workspace.hasBuildDirectory
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
    fun <T : ProcessTask> register(type: Class<T>, suffix: String, output: String?, vararg arguments: String, fileOutput: String? = null, taskName: String? = null): TaskProvider<T> {
        output?.let { component.generatedOutputs.add("$it/**") }
        fileOutput?.let(component.generatedOutputs::add)
        val taskName = taskName ?: name + suffix.split(Regex("[^A-Za-z0-9]+"))
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        return project.tasks.register(taskName, type) {
            group = "dependeasy"
            dependsOn(install, runtime.install, component.prerequisites)
            workingDirectory.set(directory)
            executable.set(workspace.tools.pnpm.map { it.asFile.absolutePath })
            environment.putAll(workspace.tools.environment)
            this.arguments.set(arguments.toList())
            toolVersion.set("pnpm ${ToolchainVersions.Pnpm} / node ${ToolchainVersions.Node}")
            sourceFiles.from(project.fileTree(directory) {
                if (directory == project.projectDir && project.hasBuildDirectory(project.file("src")))
                    include("package.json", "*config.*", "src/**/typescript/**", "ts/**", "js/**", "scripts/**", "test/**")
                exclude("**/node_modules/**", "**/dist/**", "**/build/**", "**/.git/**", "**/.gradle/**", "**/.kotlin/**", "**/*.tsbuildinfo", ".wrangler/**", ".vite/**", "coverage/**", ".cache-*/**", "logs/**", "*.log", "wrangler.dependeasy-*.json", "$output/**")
                if (directory == project.projectDir) exclude("ts/export/**")
                exclude(component.generatedOutputs)
            })
            sourceFiles.from(workspace.directory.resolve("pnpm-lock.yaml"), component.sourceInputs, runtime.sources)
            sourceFiles.from(component.contract.flatMap { it.contract }, component.contract.flatMap { it.typeScriptConfiguration })
            if (fileOutput != null) outputFiles.from(directory.resolve(fileOutput))
            else if (output != null) outputDirectories.from(directory.resolve(output))
            else if (type != WranglerDeploy::class.java) receiptFile.set(project.layout.buildDirectory.file("dependeasy/checks/$taskName.properties"))
        }.also {
            val build = dev.shibasis.dependeasy.workspace.BuildWorkspace.get(project)
            build.reference(project, it, null)
            runtime.output?.let { output -> build.consume(project.node(it), output) }
            component.kotlinOutputs.forEach { output -> build.consume(project.node(it), output) }
            val node = project.node(it)
            component.commands.add(node)
            component.packageOutputs.forEach { output -> build.consume(node, output) }
            build.connect(node, listOf(project.node(component.contract), project.rootProject.node(install)))
            if (type != PackageCommand::class.java && type != WranglerDeploy::class.java) {
                val node = project.node(it)
                if (type != dev.shibasis.dependeasy.cloudflare.WorkerSecrets::class.java)
                    build.alias(directory, if (type == TypeScriptCheck::class.java && suffix == "types") "typecheck" else suffix, node)
                build.alias(project.rootDir, taskName, node)
            }
        }
    }
}
