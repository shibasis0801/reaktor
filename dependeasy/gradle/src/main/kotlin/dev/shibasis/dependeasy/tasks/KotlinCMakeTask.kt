package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.model.KernelSpec
import dev.shibasis.dependeasy.model.ToolRef
import org.gradle.work.DisableCachingByDefault
import dev.shibasis.dependeasy.files.deleteTreeSafely
import dev.shibasis.dependeasy.native.CMakeLibraries
import dev.shibasis.dependeasy.native.cmake
import dev.shibasis.dependeasy.native.prefabLibrary
import dev.shibasis.dependeasy.model.NativeContract
import dev.shibasis.dependeasy.model.BuildModelReader
import dev.shibasis.dependeasy.toolchain.cmakeToolchainContract

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
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
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.CMakeBuild(sourceDirectory.get().asFile.absolutePath, buildDirectory.get().asFile.absolutePath, cmakeExecutable.get(), generator.get(), configuration.get(), buildTarget.get(), configureArguments.get(), environment.get().keys.sorted(), ToolRef("cmake", toolVersion.get()), toolchainContract.get().asFile.absolutePath, ninjaExecutable.get(), parallelism.get(), nativeModelFile.get().asFile.absolutePath)

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

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val toolchainContract: RegularFileProperty

    @get:Input
    abstract val cmakeExecutable: Property<String>

    @get:Input
    abstract val ninjaExecutable: Property<String>

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

    @get:Input abstract val nativeContract: Property<String>
    @get:OutputFile abstract val nativeContractFile: RegularFileProperty
    @get:OutputFile abstract val nativeModelFile: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencyManifests: ConfigurableFileCollection
    @get:Input abstract val dependencyIncludes: MapProperty<String, List<String>>
    @get:Input abstract val prefabDirectories: MapProperty<String, String>
    @get:Input abstract val prefabTargets: MapProperty<String, String>

    init {
        val tools = dev.shibasis.dependeasy.toolchain.NativeBuildTools.get(project)
        dependsOn(tools.cmake.installation, tools.ninja.installation)
        cmakeExecutable.convention(tools.cmake.executable.map { it.asFile.absolutePath })
        executionPlatform.convention(dev.shibasis.dependeasy.model.HostPlatform.Portable)
        generator.convention("Ninja")
        ninjaExecutable.convention(tools.ninja.executable.map { it.asFile.absolutePath })
        inputs.files(tools.cmake.executable, tools.ninja.executable)
        toolchainContract.convention(project.cmakeToolchainContract().flatMap { it.contract })
        staticLibrariesManifest.convention(buildDirectory.file(CMakeLibraries.MANIFEST))
        environment.convention(emptyMap())
        configuration.convention("Release")
        configureArguments.convention(emptyList())
        nativeContract.convention(buildTarget.map { BuildModelReader.json.encodeToString(NativeContract(library = it, platform = "host")) })
        val contractPath = buildTarget.zip(nativeContract) { name, declaration ->
            "dependeasy/cmake/$name-${BuildModelReader.json.decodeFromString<NativeContract>(declaration).platform.replace('/', '-')}"
        }
        nativeContractFile.convention(project.layout.buildDirectory.file(contractPath.map { "$it.cmake" }))
        nativeModelFile.convention(project.layout.buildDirectory.file(contractPath.map { "$it.json" }))
        dependencyIncludes.convention(emptyMap()); prefabDirectories.convention(emptyMap()); prefabTargets.convention(emptyMap())
        parallelism.convention(providers.environmentVariable("CMAKE_BUILD_PARALLEL_LEVEL").map { it.toInt() }.orElse(3))
        toolVersion.convention("cmake ${tools.cmake.handle.version} / ninja ${tools.ninja.handle.version}")
    }

    @TaskAction
    fun build() {
        val sourceDir = sourceDirectory.get().asFile
        val buildDir = buildDirectory.get().asFile
        var contract = BuildModelReader.json.decodeFromString<NativeContract>(nativeContract.get())
        val imports = dependencyManifests.files.sorted().map { manifest ->
            CMakeLibraries.primary(manifest).let { it.copy(includes = dependencyIncludes.get()[it.target].orEmpty()) }
        } + prefabDirectories.get().map { (name, directory) -> prefabLibrary(java.io.File(directory), contract.settings.getValue("ANDROID_ABI"), prefabTargets.get().getValue(name)) }
        val settings = contract.settings.toMutableMap()
        imports.sortedBy { it.target }.forEach { library -> when(library.target) {
            "OpenSSL::Crypto" -> { settings["OPENSSL_CRYPTO_LIBRARY"] = library.artifact; settings["OPENSSL_INCLUDE_DIR"] = library.includes.single(); settings["OPENSSL_LIBRARIES"] = library.artifact }
            "OpenSSL::SSL" -> { settings["OPENSSL_SSL_LIBRARY"] = library.artifact; settings["OPENSSL_LIBRARIES"] = library.artifact + ";" + settings["OPENSSL_CRYPTO_LIBRARY"] }
        } }
        contract = contract.copy(imports = contract.imports + imports, settings = settings, linkLibraries = (contract.linkLibraries + imports.map { it.target }).distinct())
        nativeModelFile.get().asFile.apply { parentFile.mkdirs(); writeText(BuildModelReader.json.encodeToString(contract) + "\n") }
        val generatedContract = contract.cmake()
        val nativeFile = nativeContractFile.get().asFile.apply { parentFile.mkdirs(); writeText(generatedContract) }
        val configuration = listOf(sourceIdentity, generator.get(), cmakeExecutable.get(), ninjaExecutable.get(), toolVersion.get(), environment.get().toSortedMap().toString(), toolchainContract.get().asFile.readText(), generatedContract) + configureArguments.get()
        val serializedConfiguration = configuration.joinToString("\n")
        val configured = buildDir.resolve("configure-arguments.txt")
        if (!configured.isFile || configured.readText() != serializedConfiguration) {
            buildDir.resolve("CMakeCache.txt").delete()
            buildDir.resolve("CMakeFiles").deleteTreeSafely(within = buildDir)
        }
        buildDir.mkdirs()
        CMakeLibraries.request(buildDir)

        execOperations.exec {
            workingDir = sourceDir
            executable = cmakeExecutable.get()
            environment(this@KotlinCMakeTask.environment.get())
            args(
                "-C", toolchainContract.get().asFile.absolutePath,
                "-C", nativeFile.absolutePath,
                "-DDEPE_NATIVE_CONTRACT=${nativeFile.absolutePath}",
                "-DDEPE_TOOLCHAIN_CONTRACT=${toolchainContract.get().asFile.absolutePath}",
                "-S", sourceDir.absolutePath,
                "-B", buildDir.absolutePath,
                "-G", generator.get(),
                "-DCMAKE_MAKE_PROGRAM=${ninjaExecutable.get()}",
            )
            args(configureArguments.get())
        }
        configured.writeText(serializedConfiguration)

        execOperations.exec {
            workingDir = sourceDir
            executable = cmakeExecutable.get()
            environment(this@KotlinCMakeTask.environment.get())
            args(
                "--build", buildDir.absolutePath,
                "--config", this@KotlinCMakeTask.configuration.get(),
                "--parallel", parallelism.get().toString(),
                "--target", buildTarget.get(),
            )
        }
        CMakeLibraries.record(buildDir, buildTarget.get(), this.configuration.get())
    }
}
