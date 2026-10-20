package dev.shibasis.dependeasy.native

import dev.shibasis.dependeasy.model.OptionSource
import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.toolchain.buildHost
import com.android.build.api.dsl.LibraryExtension
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.tasks.droidCmake
import dev.shibasis.dependeasy.tasks.registerPrefabTask
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/** Packages declared native outputs and the runtime from the compiler's own NDK. */
internal fun Project.configureAndroidNative() {
    val native = nativeConfigurationOrNull?.takeIf { it.isEnabled } ?: return
    native.android.declare(native.resolvedDependencies)
    val sdk = option(BuildOption.environment("ANDROID_HOME")).orElse(option(BuildOption.environment("ANDROID_SDK_ROOT")))
        .orElse(option(BuildOption.string("sdk.dir").copy(resolutionOrder = listOf(OptionSource.LocalProperties))))
        .orNull ?: error("Android SDK is required for native compilation")
    val host = if (buildHost.mac) "darwin-x86_64" else "linux-x86_64"
    val runtime = file("$sdk/ndk/${Versions.SDK.ndkVersion}/toolchains/llvm/prebuilt/$host/sysroot/usr/lib")
    val builds = native.resolvedAbis.mapNotNull { abi -> droidCmake(abi, sdk) }
    val external = native.android.resolvedPrefabs.filter { it.runtimeDependency != it.dependencyNotation }
        .map { it to registerPrefabTask(it) }
    val output = layout.buildDirectory.dir("dependeasy/jniLibs")
    val libraries = tasks.register<NativeLibrariesTask>("copyNativeLibs") {
        dependsOn(builds)
        native.resolvedAbis.forEach { abi ->
            from(nativeBuildDirectory("android/$abi")) { include("*.so"); into(abi) }
            from(runtime.resolve("${androidTriple(abi)}/libc++_shared.so")) { into(abi) }
            external.forEach { (_, prefab) ->
                from(prefab.flatMap { it.outputDirectory }.map { it.dir("libs/android.$abi") }) {
                    include("*.so"); exclude("libc++_shared.so"); into(abi)
                }
            }
        }
        generatedDirectory.set(output)
    }
    extensions.getByType(com.android.build.api.variant.LibraryAndroidComponentsExtension::class.java).onVariants { variant ->
        checkNotNull(variant.sources.jniLibs) { "Android native sources are unavailable for ${variant.name}" }
            .addGeneratedSourceDirectory(libraries, NativeLibrariesTask::generatedDirectory)
    }
}

private fun androidTriple(abi: String): String = when (abi) {
    "arm64-v8a" -> "aarch64-linux-android"
    "armeabi-v7a" -> "arm-linux-androideabi"
    "x86" -> "i686-linux-android"
    "x86_64" -> "x86_64-linux-android"
    else -> error("Unsupported Android ABI: $abi")
}
