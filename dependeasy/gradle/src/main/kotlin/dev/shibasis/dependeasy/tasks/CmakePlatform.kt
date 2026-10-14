package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.Versions
import org.gradle.api.Project
import org.gradle.api.provider.Provider

sealed class CmakePlatform(
    val variant: String,
    val generator: String,
    val taskPrefix: String,
) {
    abstract fun flags(project: Project): Provider<List<String>>

    open fun identity(project: Project): Provider<String> {
        val tools = dev.shibasis.dependeasy.toolchain.NativeBuildTools.get(project)
        val identity = "cmake ${tools.cmake.handle.version} / ninja ${tools.ninja.handle.version}"
        return project.provider { identity }
    }

    class Darwin(val sdk: String) : CmakePlatform(
        variant = sdk,
        generator = "Ninja",
        taskPrefix = sdk,

    ) {
        override fun flags(project: Project): Provider<List<String>> {
            return project.xcrunFind("clang").zip(project.xcrunFind("clang++")) { cCompiler, cxxCompiler ->
                listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-Dsdk=$sdk",
                    "-DiOS=true",
                    "-DCMAKE_C_COMPILER=$cCompiler",
                    "-DCMAKE_CXX_COMPILER=$cxxCompiler",
                )
            }
        }

        override fun identity(project: Project): Provider<String> = super.identity(project)
            .zip(project.providers.exec {
                commandLine("/usr/bin/xcrun", "--sdk", sdk, "clang++", "--version")
            }.standardOutput.asText) { cmake, compiler -> "$cmake / ${compiler.trim()}" }
            .zip(project.providers.exec {
                commandLine("/usr/bin/xcrun", "--sdk", sdk, "--show-sdk-version")
            }.standardOutput.asText) { tools, version -> "$tools / $sdk ${version.trim()}" }

        private fun Project.xcrunFind(tool: String): Provider<String> = providers.exec {
            commandLine("/usr/bin/xcrun", "--sdk", sdk, "--find", tool)
        }.standardOutput.asText.map { it.trim() }
    }

    class Android(
        val abi: String,
        val ndkDir: String,
        val cmakePath: String,
        val ninjaPath: String,
        val minSdk: Int = Versions.SDK.minSdk,
        val stl: String = "c++_shared",
    ) : CmakePlatform(
        variant = "android/$abi",
        generator = "Ninja",
        taskPrefix = "android_$abi",
    ) {
        override fun identity(project: Project): Provider<String> = super.identity(project)
            .zip(project.providers.fileContents(project.layout.projectDirectory.file("$ndkDir/source.properties")).asText) { cmake, ndk -> "$cmake / ${ndk.trim()}" }

        override fun flags(project: Project): Provider<List<String>> = project.provider {
            val toolchain = "$ndkDir/build/cmake/android.toolchain.cmake"
            listOf(
                "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
                "-DANDROID_ABI=$abi",
                "-DANDROID_STL=$stl",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DANDROID_PLATFORM=android-$minSdk",
                "-DANDROID=true",
            )
        }
    }
}
