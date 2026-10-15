package dev.shibasis.dependeasy.native

import dev.shibasis.dependeasy.model.ToolHandle
import dev.shibasis.dependeasy.model.NativeContract
import dev.shibasis.dependeasy.model.BuildModelReader
import dev.shibasis.dependeasy.settings.nativeTools
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
    var tool: dev.shibasis.dependeasy.toolchain.InstalledTool = dev.shibasis.dependeasy.toolchain.NativeBuildTools.get(project).cmake
    var generator: String = "Ninja"
    val inputs = mutableListOf<Any>()
    private val sourceLibraries = linkedMapOf<ToolHandle, String>()

    fun sourceLibrary(tool: ToolHandle, includeVariable: String) { sourceLibraries[tool] = includeVariable }

    internal fun register(): TaskProvider<KotlinCMakeTask> = project.tasks.register<KotlinCMakeTask>("${name}CMake") {
        group = "dependeasy"
        sourceDirectory.set(project.file(this@CMakeBuild.source))
        sourceFiles.from(project.cmakeSources(project.file(this@CMakeBuild.source)), project.cmakeKernelSources(), this@CMakeBuild.inputs)
        sourceLibraries.forEach { (tool, variable) ->
            val producer = project.nativeTools.sourceTasks.getValue(tool)
            dependsOn(producer)
            sourceFiles.from(producer.flatMap { it.checkout }.map { it.asFileTree.matching { exclude(".git/**", "build/**", "debug/**") } })
        }
        buildDirectory.set(project.layout.buildDirectory.dir("dependeasy/native/${this@CMakeBuild.name}/${this@CMakeBuild.configuration}"))
        dependsOn(this@CMakeBuild.tool.installation)
        cmakeExecutable.set(this@CMakeBuild.tool.executable.map { it.asFile.absolutePath })
        this.generator.set(this@CMakeBuild.generator)
        this.configuration.set(this@CMakeBuild.configuration)
        buildTarget.set(this@CMakeBuild.target)
        val settings = mapOf("CMAKE_BUILD_TYPE" to this@CMakeBuild.configuration, "CMAKE_CXX_STANDARD" to ToolchainVersions.CppStandard, "CMAKE_CXX_EXTENSIONS" to "OFF") +
            sourceLibraries.map { (tool, variable) -> variable to project.nativeTools.sourceTasks.getValue(tool).get().checkout.get().asFile.resolve("include").absolutePath }
        nativeContract.set(BuildModelReader.json.encodeToString(NativeContract(library = this@CMakeBuild.target, platform = "host", settings = settings,
            sources = sourceLibraries.keys.map { tool ->
                val dependency = Native.sources.single { it.source == tool }
                NativeContract.Source(dependency.name, dependency.repository, dependency.revision, dependency.checksum, dependency.checksumAlgorithm, dependency.recipe,
                    project.nativeTools.sourceTasks.getValue(tool).get().checkout.get().asFile.absolutePath)
            })))
        toolVersion.set("${this@CMakeBuild.tool.handle.name} ${this@CMakeBuild.tool.handle.version}")
    }
}
