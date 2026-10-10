package dev.shibasis.dependeasy.dagger

import dev.shibasis.dependeasy.workspace.BuildTarget
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

class DaggerModule internal constructor(private val project: Project, private val targets: Map<String, BuildTarget>) {
    var className = project.name.replaceFirstChar(Char::uppercaseChar)
    var browserImage = "mcr.microsoft.com/playwright:v1.64.0-noble"
    val gradleProperties = linkedMapOf<String, String>()

    internal fun register(directory: Any): org.gradle.api.tasks.TaskProvider<GenerateDaggerTask> {
        fun generation(name: String, output: Any, config: Any) = project.tasks.register<GenerateDaggerTask>(name) {
            group = "code generation"
            moduleName.set(className)
            buildName.set(project.name)
            engineVersion.set(ToolchainVersions.Dagger)
            moduleSource.set(project.relativePath(directory))
            moduleConfiguration.set(project.file(config))
            browserImage.set(this@DaggerModule.browserImage)
            gradleProperties.set(this@DaggerModule.gradleProperties)
            declarations.set(targets.values.map { it.json() })
            runtimeSource.set(project.rootDir.resolve("../reaktor/dependeasy/dagger/runtime.ts"))
            outputDirectory.set(project.file(output))
        }
        val reference = generation("generateDaggerReference", project.layout.buildDirectory.dir("dependeasy/dagger-reference/src"),
            project.layout.buildDirectory.file("dependeasy/dagger-reference/dagger.json"))
        val generated = generation("generateDagger", project.file(directory).resolve("src"), "dagger.json")
        project.tasks.register<VerifyDaggerTask>("verifyDagger") {
            group = "verification"
            mustRunAfter(generated)
            expected.set(reference.flatMap { it.outputDirectory })
            actual.set(project.file(directory).resolve("src"))
            expectedConfiguration.set(reference.flatMap { it.moduleConfiguration })
            actualConfiguration.set(project.layout.projectDirectory.file("dagger.json"))
        }
        return generated
    }
}
