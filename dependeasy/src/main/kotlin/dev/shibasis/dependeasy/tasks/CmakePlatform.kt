package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.Versions
import org.gradle.api.Project
import org.gradle.api.provider.Provider

sealed class CmakePlatform(
    val variant: String,
    val generator: String,
    val taskPrefix: String,
    val cmakeExecutable: String = "cmake",
) {
    abstract fun flags(project: Project): Provider<List<String>>

    open fun identity(project: Project): Provider<String> = project.providers.exec {
        commandLine(cmakeExecutable, "--version")
    }.standardOutput.asText.map { it.trim() }

    class Darwin(val sdk: String) : CmakePlatform(
        variant = sdk,
        generator = "Ninja",
        taskPrefix = sdk,
        cmakeExecutable = listOf("/opt/homebrew/bin/cmake", "/usr/local/bin/cmake", "cmake").first {
            java.io.File(it).exists() || it == "cmake"
        }
    ) {
        override fun flags(project: Project): Provider<List<String>> {
            return project.xcrunFind("clang").zip(project.xcrunFind("clang++")) { cCompiler, cxxCompiler ->
                listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-Dsdk=$sdk",
                    "-DiOS=true",
                    "-DCMAKE_MAKE_PROGRAM=${listOf("/opt/homebrew/bin/ninja", "/usr/local/bin/ninja", "ninja").first {
                        java.io.File(it).exists() || it == "ninja"
                    }}",
                    "-DCMAKE_C_COMPILER=$cCompiler",
                    "-DCMAKE_CXX_COMPILER=$cxxCompiler",
                )
            }
        }

        override fun identity(project: Project): Provider<String> = super.identity(project)
            .zip(project.providers.exec {
                commandLine("xcrun", "--sdk", sdk, "clang++", "--version")
            }.standardOutput.asText) { cmake, compiler -> "$cmake / ${compiler.trim()}" }
            .zip(project.providers.exec {
                commandLine("xcrun", "--sdk", sdk, "--show-sdk-version")
            }.standardOutput.asText) { tools, version -> "$tools / $sdk ${version.trim()}" }

        private fun Project.xcrunFind(tool: String): Provider<String> = providers.exec {
            commandLine("xcrun", "--sdk", sdk, "--find", tool)
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
        cmakeExecutable = cmakePath,
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
                "-DCMAKE_VERBOSE_MAKEFILE=1",
                "-DCMAKE_MAKE_PROGRAM=$ninjaPath",
            )
        }
    }
}
