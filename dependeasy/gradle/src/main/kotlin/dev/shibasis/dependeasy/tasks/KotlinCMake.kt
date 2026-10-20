package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.toolchain.buildHost
import dev.shibasis.dependeasy.handles.publishOutput
import dev.shibasis.dependeasy.model.Apple
import dev.shibasis.dependeasy.model.NativeContract
import dev.shibasis.dependeasy.model.BuildModelReader
import dev.shibasis.dependeasy.model.OutputKind
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.native.cmakeKernelSources
import dev.shibasis.dependeasy.native.cmakeSources
import dev.shibasis.dependeasy.native.nativeBuildDirectory
import dev.shibasis.dependeasy.native.nativeConfigurationOrNull
import dev.shibasis.dependeasy.native.nativeProjectDependencies
import dev.shibasis.dependeasy.toolchain.NativeBuildTools
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

fun Project.kotlinCmake(platform: CmakePlatform): TaskProvider<KotlinCMakeTask>? {
    val taskName = "${platform.taskPrefix}CMake"
    val key = KotlinCMakeTask::class.java.name
    @Suppress("UNCHECKED_CAST")
    val registered = if (extensions.extraProperties.has(key)) extensions.extraProperties.get(key) as MutableMap<String, TaskProvider<KotlinCMakeTask>>
        else linkedMapOf<String, TaskProvider<KotlinCMakeTask>>().also { extensions.extraProperties.set(key, it) }
    registered[platform.variant]?.let { return it }
    val native = nativeConfigurationOrNull?.takeIf { it.isEnabled && it.supports(platform) } ?: return null
    native.android.declare(native.resolvedDependencies)
    val nativeDependencies = nativeProjectDependencies()
    val appleHost = platform is CmakePlatform.Darwin || platform is CmakePlatform.Host && buildHost.mac
    val sourceDependencies = (native.resolvedDependencies + nativeDependencies.flatMap { it.project.nativeConfigurationOrNull!!.resolvedDependencies }).distinctBy { it.name }
        .filter { (it.recipe != "foundation" || appleHost) && (it.recipe != "prefab" || platform is CmakePlatform.Android) }
    val dependencyTasks = nativeDependencies.mapNotNull { dependency ->
        dependency.project.kotlinCmake(platform)?.let { dependency to it }
    }
    val prefabTasks = if (platform is CmakePlatform.Android) {
        native.android.resolvedPrefabs.map { it to registerPrefabTask(it) }
    } else emptyList()

    val buildTask = tasks.register<KotlinCMakeTask>(taskName) {
        group = "reaktor"
        sourceDirectory.set(native.resolvedCmakeLists.parentFile)
        sourceFiles.from(project.cmakeSources(native.resolvedSourceDirectory), project.cmakeKernelSources())
        buildDirectory.set(project.nativeBuildDirectory(platform.variant))
        executionPlatform.set(if (platform is CmakePlatform.Darwin) dev.shibasis.dependeasy.model.HostPlatform.Macos else dev.shibasis.dependeasy.model.HostPlatform.Portable)
        generator.set(platform.generator)
        toolVersion.set(platform.identity(project))
        buildTarget.set(native.resolvedLibraryName)
        val contractPath = "dependeasy/cmake/${native.resolvedLibraryName}-${platform.variant.replace('/', '-')}"
        nativeContractFile.set(project.layout.buildDirectory.file("$contractPath.cmake"))
        nativeModelFile.set(project.layout.buildDirectory.file("$contractPath.json"))
        val hostRoots = if (platform is CmakePlatform.Android) emptyMap() else
            native.resolvedDependencies.flatMap { it.hostRoots }.distinct().mapNotNull { variable ->
                NativeBuildTools.get(project).hostDirectory(variable).get().takeIf(String::isNotBlank)?.let { variable to it }
            }.toMap()
        val includes = native.resolvedIncludeDirs
        val libraryName = native.resolvedLibraryName
        val sources = project.nativeSourceContracts(sourceDependencies.filter { it.recipe != "foundation" }, platform, emptyMap())
        val foundationSource = sourceDependencies.firstOrNull { it.recipe == "foundation" }?.let { dependency -> NativeContract.Source(dependency.name, dependency.repository, "", "", dependency.checksumAlgorithm, dependency.recipe) }
        nativeContract.set(platform.settings(project).map { settings ->
            val hermes = sourceDependencies.any { it.recipe == "hermes" }
            val foundation = sourceDependencies.any { it.recipe == "foundation" }
            BuildModelReader.json.encodeToString(NativeContract(library = libraryName, platform = platform.variant,
                settings = settings + hostRoots +
                    (if (platform is CmakePlatform.Darwin && hostRoots.isNotEmpty()) mapOf("CMAKE_FIND_ROOT_PATH" to hostRoots.values.distinct().joinToString(";")) else emptyMap()) + mapOf("CMAKE_CXX_STANDARD" to ToolchainVersions.CppStandard, "CMAKE_CXX_EXTENSIONS" to "OFF"),
                includeDirectories = includes,
                linkLibraries = (if(platform is CmakePlatform.Android) listOf("android", "log") else emptyList()) +
                    (if(hermes) if(platform is CmakePlatform.Android) listOf("jsi") else listOf("hermesvm", "jsi") else emptyList()) +
                    (if(foundation) listOf("-framework Foundation") else emptyList()),
                compileOptions = if(foundation) listOf("-fobjc-arc") else emptyList(),
                linkOptions = if(platform is CmakePlatform.Android) listOf("-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384") else emptyList(),
                sources = sources + listOfNotNull(foundationSource?.copy(revision = settings.getValue("DEPE_SDK_VERSION"), checksum = settings.getValue("DEPE_SDK_METADATA_SHA256")))))
        })
        includeNativeDependencies(dependencyTasks)
        includePrefabs(prefabTasks)
        includeToolSources(project, sourceDependencies, platform)
    }
    registered[platform.variant] = buildTask
    if (platform is CmakePlatform.Darwin) {
        val apple = when (platform.sdk) {
            "iphoneos" -> Apple.IosArm64
            "iphonesimulator" -> Apple.IosSimulatorArm64
            else -> error("Unsupported Apple SDK ${platform.sdk}")
        }
        publishOutput(OutputKind.NativeLibrary, buildTask.flatMap { it.buildDirectory }, apple, producer = buildTask)
    }
    val inputs = prefabTasks.map { node(it.second) } + dependencyTasks.map { (dependency, task) -> dependency.project.node(task) }
    node(buildTask).after(*inputs.toTypedArray())
    return buildTask
}
