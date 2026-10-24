package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.model.KernelSpec
import dev.shibasis.dependeasy.model.ToolRef
import org.gradle.work.DisableCachingByDefault
import dev.shibasis.dependeasy.native.AndroidPrefabConfiguration
import dev.shibasis.dependeasy.native.CMakeLibraries
import dev.shibasis.dependeasy.model.NativeContract
import dev.shibasis.dependeasy.model.BuildModelReader
import dev.shibasis.dependeasy.model.CMake
import dev.shibasis.dependeasy.toolchain.ToolchainVersions

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

@DisableCachingByDefault(because = "CMake build trees contain absolute host paths; use incremental local builds")
abstract class KotlinCMakeTask : DefaultTask(), KernelSpecOwner {
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.CMakeBuild(sourceDirectory.get().asFile.absolutePath, buildDirectory.get().asFile.absolutePath, cmakeExecutable.get(), generator.get(), configuration.get(), buildTarget.get(), CMake.toolchain(ToolchainVersions.Cmake.substringBeforeLast('.'), ToolchainVersions.CppStandard.toInt(), configuration.get()) + configureArguments.get(), environment.get().keys.sorted(), ToolRef("cmake", toolVersion.get()), nativeContractFile.get().asFile.absolutePath, ninjaExecutable.get(), parallelism.get(), nativeModelFile.get().asFile.absolutePath, nativeContractFile.get().asFile.absolutePath, ToolRef("ninja", ninjaVersion.get()), nativeExecutables.get())

    @get:Inject
    abstract val providers: org.gradle.api.provider.ProviderFactory

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    @get:Input
    val sourceIdentity: String get() = sourceDirectory.get().asFile.canonicalPath

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val buildDirectory: DirectoryProperty

    @get:Internal
    abstract val staticLibrariesManifest: RegularFileProperty

    @get:Input
    abstract val cmakeExecutable: Property<String>

    @get:Input
    abstract val ninjaExecutable: Property<String>

    @get:Input abstract val ninjaVersion: Property<String>

    @get:Input
    abstract val executionPlatform: Property<dev.shibasis.dependeasy.model.HostPlatform>
    @get:Internal override val kernelHost: dev.shibasis.dependeasy.model.HostRequirement get() = dev.shibasis.dependeasy.model.HostRequirement(executionPlatform.get())

    @get:Input
    abstract val generator: Property<String>

    @get:Input
    abstract val configureArguments: ListProperty<String>

    @get:Input
    abstract val buildTarget: Property<String>

    @get:Input
    abstract val configuration: Property<String>

    @get:Input
    abstract val parallelism: Property<Int>

    @get:Input
    abstract val environment: MapProperty<String, String>

    @get:Input
    abstract val toolVersion: Property<String>

    @get:Input abstract val nativeExecutables: ListProperty<NativeContract.Executable>
    @get:Input abstract val nativeContract: Property<String>
    @get:OutputFile abstract val nativeContractFile: RegularFileProperty
    @get:OutputFile abstract val nativeModelFile: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencyManifests: ConfigurableFileCollection
    @get:Input abstract val dependencyIncludes: MapProperty<String, List<String>>
    @get:Input abstract val prefabDirectories: MapProperty<String, String>
    @get:Input abstract val prefabDeclarations: MapProperty<String, AndroidPrefabConfiguration>

    init {
        val tools = dev.shibasis.dependeasy.toolchain.NativeBuildTools.get(project)
        dependsOn(tools.cmake.installation, tools.ninja.installation)
        cmakeExecutable.convention(tools.cmake.executable.map { it.asFile.absolutePath })
        executionPlatform.convention(dev.shibasis.dependeasy.model.HostPlatform.Portable)
        generator.convention("Ninja")
        ninjaExecutable.convention(tools.ninja.executable.map { it.asFile.absolutePath })
        ninjaVersion.convention(tools.ninja.handle.version)
        inputs.files(tools.cmake.executable, tools.ninja.executable)
        staticLibrariesManifest.convention(buildDirectory.file(CMakeLibraries.MANIFEST))
        environment.convention(emptyMap())
        configuration.convention("Release")
        configureArguments.convention(emptyList())
        nativeExecutables.convention(emptyList())
        nativeContract.convention(buildTarget.map { BuildModelReader.json.encodeToString(NativeContract(library = it, platform = "host")) })
        val contractPath = buildTarget.zip(nativeContract) { name, declaration ->
            "dependeasy/cmake/$name-${BuildModelReader.json.decodeFromString<NativeContract>(declaration).platform.replace('/', '-')}"
        }
        nativeContractFile.convention(project.layout.buildDirectory.file(contractPath.map { "$it.cmake" }))
        nativeModelFile.convention(project.layout.buildDirectory.file(contractPath.map { "$it.json" }))
        dependencyIncludes.convention(emptyMap()); prefabDirectories.convention(emptyMap()); prefabDeclarations.convention(emptyMap())
        parallelism.convention(project.option(BuildOption.environment("CMAKE_BUILD_PARALLEL_LEVEL", "3")).map { it.toInt() })
        toolVersion.convention("cmake ${tools.cmake.handle.version} / ninja ${tools.ninja.handle.version}")
    }

    @TaskAction
    fun build() {
        val spec = kernelSpec as KernelSpec.CMakeBuild
        val contract = dev.shibasis.dependeasy.model.CMakeBuildKernel.resolve(
            BuildModelReader.json.decodeFromString<NativeContract>(nativeContract.get()).let { it.copy(executables = it.executables + spec.executables) }, dependencyManifests.files.toList(),
            dependencyIncludes.get(), prefabDirectories.get(), prefabDeclarations.get())
        val environment = environment.get()
        val identity = dev.shibasis.dependeasy.model.CMakeBuildKernel.prepare(spec, contract, environment)
        val plan = dev.shibasis.dependeasy.model.CMakeBuildKernel.plan(spec)
        fun execute(execution: dev.shibasis.dependeasy.model.ProcessExecution) {
            execOperations.exec {
                workingDir = java.io.File(execution.directory)
                executable = execution.executable
                environment(environment)
                args(execution.arguments)
            }
        }
        execute(plan[0])
        dev.shibasis.dependeasy.model.CMakeBuildKernel.configured(spec, identity)
        execute(plan[1])
        dev.shibasis.dependeasy.model.CMakeBuildKernel.completed(spec)
    }

}
