package dev.shibasis.dependeasy.native

import com.android.build.api.dsl.LibraryExtension
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.tasks.droidCmake
import dev.shibasis.dependeasy.tasks.registerPrefabTask
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.register

/** Packages declared native outputs and the runtime from the compiler's own NDK. */
internal fun Project.configureAndroidNative() {
    val native = nativeConfigurationOrNull?.takeIf { it.isEnabled } ?: return
    val sdk = providers.environmentVariable("ANDROID_HOME").orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
        .orNull ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { file ->
            java.util.Properties().apply { file.reader().use(::load) }.getProperty("sdk.dir")
        } ?: error("Android SDK is required for native compilation")
    val host = if (providers.systemProperty("os.name").get().contains("Mac", true)) "darwin-x86_64" else "linux-x86_64"
    val runtime = file("$sdk/ndk/${Versions.SDK.ndkVersion}/toolchains/llvm/prebuilt/$host/sysroot/usr/lib")
    val builds = Versions.architectures.mapNotNull { abi -> droidCmake(abi, sdk) }
    val external = native.android.resolvedPrefabs.filter { it.runtimeDependency != it.dependencyNotation }
        .map { it to registerPrefabTask(it) }
    val output = layout.buildDirectory.dir("dependeasy/jniLibs")
    val libraries = tasks.register<Sync>("copyNativeLibs") {
        dependsOn(builds)
        Versions.architectures.forEach { abi ->
            from(nativeBuildDirectory("android/$abi")) { include("*.so"); into(abi) }
            from(runtime.resolve("${androidTriple(abi)}/libc++_shared.so")) { into(abi) }
            external.forEach { (_, prefab) ->
                from(prefab.flatMap { it.outputDirectory }.map { it.dir("libs/android.$abi") }) {
                    include("*.so"); exclude("libc++_shared.so"); into(abi)
                }
            }
        }
        into(output)
    }
    tasks.matching {
        it.name != "copyNativeLibs" && (it.name.contains("JniLib", true) ||
            it.name.contains("NativeLib", true) || it.name.contains("DebugSymbols", true))
    }.configureEach { dependsOn(libraries) }
    extensions.findByType(LibraryExtension::class.java)?.sourceSets?.getByName("main")?.jniLibs
        ?.setSrcDirs(listOf(output.get().asFile))
}

private fun androidTriple(abi: String): String = when (abi) {
    "arm64-v8a" -> "aarch64-linux-android"
    "armeabi-v7a" -> "arm-linux-androideabi"
    "x86" -> "i686-linux-android"
    "x86_64" -> "x86_64-linux-android"
    else -> error("Unsupported Android ABI: $abi")
}
