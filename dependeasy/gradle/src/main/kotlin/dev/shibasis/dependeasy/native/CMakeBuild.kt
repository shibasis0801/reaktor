package dev.shibasis.dependeasy.native

import dev.shibasis.dependeasy.tasks.KotlinCMakeTask
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

/** Small standalone CMake declaration, usable without a Kotlin or Android project. */
class CMakeBuild internal constructor(private val project: Project, private val name: String) {
    var source: Any = "cpp"
    var target: String = name
    var configuration: String = "Release"
    var executable: String = "cmake"
    var generator: String = "Ninja"
    val arguments = mutableListOf<String>()
    val inputs = mutableListOf<Any>()
    private val sourceLibraries = linkedMapOf<String, String>()

    fun sourceLibrary(name: String, includeVariable: String) { sourceLibraries[name] = includeVariable }

    internal fun register(): TaskProvider<KotlinCMakeTask> = project.tasks.register<KotlinCMakeTask>("${name}CMake") {
        group = "dependeasy"
        sourceDirectory.set(project.file(this@CMakeBuild.source))
        sourceFiles.from(project.cmakeSources(project.file(this@CMakeBuild.source)), project.cmakeKernelSources(), this@CMakeBuild.inputs)
        sourceLibraries.forEach { (name, variable) ->
            dependsOn(project.rootProject.tasks.named("prepare${name.replaceFirstChar(Char::uppercaseChar)}Source"))
            val directory = project.rootDir.resolve(".github_modules/$name")
            sourceFiles.from(project.fileTree(directory) { exclude(".git/**", "build/**", "debug/**") })
            configureArguments.add("-D$variable=${directory.resolve("include").absolutePath}")
        }
        buildDirectory.set(project.layout.buildDirectory.dir("dependeasy/native/${this@CMakeBuild.name}/${this@CMakeBuild.configuration}"))
        cmakeExecutable.set(this@CMakeBuild.executable)
        this.generator.set(this@CMakeBuild.generator)
        this.configuration.set(this@CMakeBuild.configuration)
        buildTarget.set(this@CMakeBuild.target)
        configureArguments.addAll(listOf("-DCMAKE_BUILD_TYPE=${this@CMakeBuild.configuration}", "-DCMAKE_CXX_STANDARD=${ToolchainVersions.CppStandard}", "-DCMAKE_CXX_EXTENSIONS=OFF") + this@CMakeBuild.arguments)
        toolVersion.set(project.providers.exec { commandLine(this@CMakeBuild.executable, "--version") }.standardOutput.asText.map { it.lineSequence().first() })
    }
}
