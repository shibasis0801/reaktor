package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.Versions
import org.gradle.api.Project
import org.gradle.api.provider.Provider

sealed class CmakePlatform(val variant: String, val generator: String, val taskPrefix: String) {
    abstract fun settings(project: Project): Provider<Map<String, String>>
    fun flags(project: Project): Provider<List<String>> = settings(project).map { values -> values.map { (key, value) -> "-D$key=$value" } }
    open fun identity(project: Project): Provider<String> {
        val tools = dev.shibasis.dependeasy.toolchain.NativeBuildTools.get(project)
        return project.provider { "cmake ${tools.cmake.handle.version} / ninja ${tools.ninja.handle.version}" }
    }
    class Darwin(val sdk: String) : CmakePlatform(sdk, "Ninja", sdk) {
        override fun settings(project: Project): Provider<Map<String, String>> = appleSettings(project, sdk, Versions.SDK.targetDarwin, "iOS")
        override fun identity(project: Project): Provider<String> = super.identity(project).zip(settings(project)) { tools, values -> "$tools / $sdk ${values.getValue("DEPE_SDK_VERSION")} / ${values.getValue("CMAKE_CXX_COMPILER")}" }
    }
    class Host : CmakePlatform("host", "Ninja", "host") {
        override fun settings(project: Project): Provider<Map<String, String>> = if(project.providers.systemProperty("os.name").get().contains("Mac", true))
            appleSettings(project, "macosx", Versions.SDK.targetMacos, "Darwin")
        else project.provider { mapOf("CMAKE_BUILD_TYPE" to "Release") }
    }
    class Android(val abi: String, val ndkDir: String, val cmakePath: String, val ninjaPath: String,
        val minSdk: Int = Versions.SDK.minSdk, val stl: String = "c++_shared") : CmakePlatform("android/$abi", "Ninja", "android_$abi") {
        override fun identity(project: Project): Provider<String> = super.identity(project)
            .zip(project.providers.fileContents(project.layout.projectDirectory.file("$ndkDir/source.properties")).asText) { cmake, ndk -> "$cmake / ${ndk.trim()}" }
        override fun settings(project: Project): Provider<Map<String, String>> = project.provider { mapOf(
            "CMAKE_TOOLCHAIN_FILE" to "$ndkDir/build/cmake/android.toolchain.cmake", "ANDROID_ABI" to abi,
            "ANDROID_STL" to stl, "CMAKE_BUILD_TYPE" to "Release", "ANDROID_PLATFORM" to "android-$minSdk", "ANDROID" to "true") }
    }
    companion object {
        private fun appleSettings(project: Project, sdk: String, minimum: String, system: String): Provider<Map<String, String>> {
            fun output(vararg arguments: String) = project.providers.exec { commandLine("/usr/bin/xcrun", "--sdk", sdk, *arguments) }.standardOutput.asText.map { it.trim() }
            return output("--find", "clang").zip(output("--find", "clang++")) { c, cpp -> mapOf("CMAKE_C_COMPILER" to c, "CMAKE_CXX_COMPILER" to cpp) }
                .zip(output("--show-sdk-path")) { compilers, path ->
                    val metadata = java.io.File(path, "SDKSettings.plist")
                    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(metadata.readBytes()).joinToString("") { "%02x".format(it) }
                    compilers + mapOf("CMAKE_OSX_SYSROOT" to path, "DEPE_SDK_METADATA_SHA256" to hash)
                }.zip(output("--show-sdk-version")) { values, version -> values + mapOf(
                    "CMAKE_BUILD_TYPE" to "Release", "CMAKE_SYSTEM_NAME" to system, "CMAKE_OSX_DEPLOYMENT_TARGET" to minimum,
                    "CMAKE_OSX_ARCHITECTURES" to "arm64", "CMAKE_POSITION_INDEPENDENT_CODE" to "ON", "DEPE_SDK_VERSION" to version,
                    "sdk" to sdk, "iOS" to (system == "iOS").toString(), "CMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED" to "NO",
                    "CMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_REQUIRED" to "NO", "CMAKE_XCODE_ATTRIBUTE_DEVELOPMENT_TEAM" to "") }
        }
    }
}
