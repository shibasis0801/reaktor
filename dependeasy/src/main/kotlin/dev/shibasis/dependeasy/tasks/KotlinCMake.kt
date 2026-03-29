package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.native.AndroidPrefabConfiguration
import dev.shibasis.dependeasy.native.nativeBuildDirectory
import dev.shibasis.dependeasy.native.nativeConfigurationOrNull
import dev.shibasis.dependeasy.native.nativeProjectDependencies
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

sealed class CmakePlatform(
    val variant: String,
    val generator: String,
    val taskPrefix: String,
    val cmakeExecutable: String = "cmake",
) {
    abstract fun flags(project: Project): List<String>

    class Darwin(val sdk: String) : CmakePlatform(
        variant = sdk,
        generator = "Xcode",
        taskPrefix = sdk,
        cmakeExecutable = listOf("/opt/homebrew/bin/cmake", "/usr/local/bin/cmake", "cmake").first {
            java.io.File(it).exists() || it == "cmake"
        }
    ) {
        override fun flags(project: Project): List<String> = listOf(
            "-DCMAKE_BUILD_TYPE=Release",
            "-Dsdk=$sdk",
            "-DiOS=true",
        )
    }

    class Android(
        val abi: String,
        val ndkDir: String,
        val cmakePath: String,
        val ninjaPath: String,
        val minSdk: Int = Version.SDK.minSdk,
        val stl: String = "c++_shared",
    ) : CmakePlatform(
        variant = "android/$abi",
        generator = "Ninja",
        taskPrefix = "android_$abi",
        cmakeExecutable = cmakePath,
    ) {
        override fun flags(project: Project): List<String> {
            val toolchain = "$ndkDir/build/cmake/android.toolchain.cmake"
            return listOf(
                "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
                "-DANDROID_ABI=$abi",
                "-DANDROID_STL=$stl",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DANDROID_PLATFORM=android-$minSdk",
                "-DANDROID=true",
                "-DCMAKE_VERBOSE_MAKEFILE=1",
                "-DCMAKE_MAKE_PROGRAM=$ninjaPath",
            )
        }
    }
}

fun Project.kotlinCmake(platform: CmakePlatform): TaskProvider<out Task>? {
    val native = nativeConfigurationOrNull
        ?.takeIf { it.isEnabled }
        ?: return null
    val nativeSourceDirectory = native.resolvedSourceDirectory
    val nativeCmakeLists = native.resolvedCmakeLists
    val nativeBuildDirectory = nativeBuildDirectory(platform.variant)
    val nativeDependencies = project.nativeProjectDependencies()
    val prefabTasks = when (platform) {
        is CmakePlatform.Android -> native.android.resolvedPrefabs.map { prefab ->
            prefab to registerPrefabTask(prefab)
        }
        else -> emptyList()
    }

    return tasks.register<KotlinCMakeTask>("${platform.taskPrefix}CMake") {
        group = "reaktor"
        sourceDirectory.set(nativeCmakeLists.parentFile)
        sourceFiles.from(
            project.fileTree(nativeSourceDirectory) {
                exclude("build/**")
            }
        )
        buildDirectory.set(nativeBuildDirectory)
        generator.set(platform.generator)
        cmakeExecutable.set(platform.cmakeExecutable)
        buildTarget.set(native.resolvedLibraryName)
        dependsOn(prefabTasks.map { it.second })
        configureArguments.addAll(platform.flags(project))
        if (nativeDependencies.isNotEmpty()) {
            configureArguments.add(
                "-DREAKTOR_NATIVE_DEPENDENCY_PROJECTS=${
                    nativeDependencies.joinToString(";") { it.project.name }
                }"
            )
            configureArguments.add(
                "-DREAKTOR_NATIVE_DEPENDENCY_SOURCE_DIRS=${
                    nativeDependencies.joinToString(";") { it.sourceDirectory.absolutePath }
                }"
            )
            configureArguments.add(
                "-DREAKTOR_NATIVE_DEPENDENCY_TARGETS=${
                    nativeDependencies.joinToString(";") { it.libraryName }
                }"
            )
        }
        prefabTasks.forEach { (prefab, task) ->
            configureArguments.add(
                task.flatMap { extracted ->
                    extracted.outputDirectory.map { directory ->
                        "-D${prefab.cmakeVariable}=${directory.asFile.absolutePath}"
                    }
                }
            )
        }
    }
}

private fun Project.registerPrefabTask(
    prefab: AndroidPrefabConfiguration,
): TaskProvider<ExtractPrefabTask> {
    val taskName = "extract${prefab.moduleName.replaceFirstChar(Char::uppercaseChar)}Prefab"
    val existing = tasks.findByName(taskName)
    if (existing != null) {
        @Suppress("UNCHECKED_CAST")
        return tasks.named(taskName) as TaskProvider<ExtractPrefabTask>
    }
    return tasks.register<ExtractPrefabTask>(taskName) {
        group = "reaktor"
        dependencyNotation.set(prefab.dependencyNotation)
        moduleName.set(prefab.moduleName)
        outputDirectory.set(layout.buildDirectory.dir("dependeasy/prefab/${prefab.moduleName}"))
    }
}
