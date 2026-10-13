package dev.shibasis.dependeasy.dagger

import dev.shibasis.dependeasy.workspace.BuildTarget
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import dev.shibasis.dependeasy.model.DaggerToolchainContract
import dev.shibasis.dependeasy.model.AndroidSdkContract
import dev.shibasis.dependeasy.model.BuildModelReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
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
            toolchainContract.set(Json { encodeDefaults = true }.encodeToString(DaggerToolchainContract(
                javaImage = ToolchainVersions.JavaBuildImage,
                gradleUrl = "https://services.gradle.org/distributions/gradle-${ToolchainVersions.Gradle}-bin.zip",
                gradleSha256 = ToolchainVersions.GradleSha256,
                gradleArchive = "/opt/gradle-dist/gradle-bin.zip",
                androidSdk = AndroidSdkContract(
                    directory = "/opt/android-sdk",
                    commandLineTools = ToolchainVersions.AndroidCommandLineTools,
                    downloadUrl = "https://dl.google.com/android/repository/${ToolchainVersions.AndroidCommandLineToolsArchive}",
                    sha256 = ToolchainVersions.AndroidCommandLineToolsSha256,
                    packages = listOf("platform-tools", "platforms/android-${ToolchainVersions.AndroidSdkPlatform}",
                        "build-tools/${ToolchainVersions.AndroidBuildTools}", "ndk/${ToolchainVersions.Ndk}", "cmake/${ToolchainVersions.Cmake}"),
                ),
            )))
            declarations.set(targets.values.map { BuildModelReader.encode(it.model()) })
            edges.set(targets.values.flatMap { it.edges() }.map { BuildModelReader.json.encodeToString(it) })
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
