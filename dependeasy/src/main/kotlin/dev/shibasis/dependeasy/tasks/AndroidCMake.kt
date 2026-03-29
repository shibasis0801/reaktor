package dev.shibasis.dependeasy.tasks

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

fun Project.droidCmake(
    abi: String,
    sdkDir: String,
    minSdk: Int = dev.shibasis.dependeasy.Version.SDK.minSdk,
    stl: String = "c++_shared"
): TaskProvider<out Task>? {
    val ndkDir = "$sdkDir/ndk/${dev.shibasis.dependeasy.Version.SDK.ndkVersion}"
    val cmakePath = "$sdkDir/cmake/${dev.shibasis.dependeasy.Version.SDK.CMake}/bin/cmake"
    val ninjaPath = "$sdkDir/cmake/${dev.shibasis.dependeasy.Version.SDK.CMake}/bin/ninja"
    return kotlinCmake(CmakePlatform.Android(abi, ndkDir, cmakePath, ninjaPath, minSdk, stl))
}
