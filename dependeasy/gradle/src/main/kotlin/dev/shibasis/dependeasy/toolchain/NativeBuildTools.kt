package dev.shibasis.dependeasy.toolchain

import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.workspace.selectBuildPath
import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.model.ToolHandle
import dev.shibasis.dependeasy.model.BuildModelReader
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

class InstalledTool(val handle: ToolHandle, val executable: Provider<RegularFile>, val installation: TaskProvider<InstallTool>, val environment: Provider<Map<String, String>>)

class NativeBuildTools private constructor(private val project: Project) {
    private val cache = project.gradle.gradleUserHomeDir.resolve("dependeasy/tools")
    private val host = project.buildHost
    private val mac = host.mac
    private val arm = host.arm
    private val platform = if (mac) "macos-universal" else if (arm) "linux-aarch64" else "linux-x86_64"
    private fun downloaded(name: String, version: String, url: String, checksum: String, prefix: String?, entry: String, format: ToolArchive): InstalledTool {
        val directory = cache.resolve("$name/$version/$platform")
        val task = project.tasks.register<InstallTool>("${name}Setup") {
            this.url.set(url); this.checksum.set(checksum); this.binaryName.set(entry); this.format.set(format)
            if (prefix == null) archiveEntry.set(entry) else archivePrefix.set(prefix)
            destination.set(directory)
        }
        return InstalledTool(ToolHandle(name, version), project.objects.fileProperty().fileValue(directory.resolve(entry)), task, project.provider { emptyMap() })
    }
    val cmake = downloaded("cmake", ToolchainVersions.Cmake,
        "https://github.com/Kitware/CMake/releases/download/v${ToolchainVersions.Cmake}/cmake-${ToolchainVersions.Cmake}-$platform.tar.gz",
        when (platform) {
            "macos-universal" -> "4e85de4daf1c3e82d7dc6b8ba5683972944b466343aeb9c327a742437bb3ce9a"
            "linux-aarch64" -> "391da1544ef50ac31300841caaf11db4de3976cdc4468643272e44b3f4644713"
            else -> "585ae9e013107bc8e7c7c9ce872cbdcbdff569e675b07ef57aacfb88c886faac"
        }, "cmake-${ToolchainVersions.Cmake}-$platform/" + if (mac) "CMake.app/Contents/" else "", "bin/cmake", ToolArchive.TarGzip)
    private val ninjaAsset = if (mac) "ninja-mac" else if (arm) "ninja-linux-aarch64" else "ninja-linux"
    val ninja = downloaded("ninja", ToolchainVersions.Ninja,
        "https://github.com/ninja-build/ninja/releases/download/v${ToolchainVersions.Ninja}/$ninjaAsset.zip",
        when (ninjaAsset) {
            "ninja-mac" -> "da7797794153629aca5570ef7c813342d0be214ba84632af886856e8f0063dd9"
            "ninja-linux-aarch64" -> "740f1b9f9d8ae68438240a6a2f3f7a27fc8b1946d2024a6a6b25857ee877987b"
            else -> "0830252db77884957a1a4b87b05a1e2d9b5f658b8367f82999a941884cbe0238"
        }, null, "ninja", ToolArchive.Zip)
    private fun sdk(name: String, source: Provider<String>, environment: Provider<Map<String, String>> = project.provider { emptyMap() }): InstalledTool {
        val version = project.providers.exec { commandLine(source.get(), "--version") }.standardOutput.asText.map { it.lineSequence().first().trim() }
        val binary = project.objects.fileProperty().fileProvider(source.map { java.io.File(it) })
        val directory = cache.resolve("$name/sdk/$platform")
        val task = project.tasks.register<InstallTool>("${name}Setup") {
            sdkBinary.set(binary); checksum.set(binary.map { BuildModelReader.digest(it.asFile) })
            binaryName.set(name); destination.set(directory)
        }
        return InstalledTool(ToolHandle(name, version.get()), project.objects.fileProperty().fileValue(directory.resolve(name)), task, environment)
    }
    private fun defaultPaths(variable: String) = project.option(BuildOption.systemProperty("user.home")).map { home ->
        ToolchainVersions.HostPaths.getValue(variable).map { if (it.startsWith("~/")) home + it.substring(1) else it }
    }
    fun hostFile(variable: String): Provider<String> = project.selectBuildPath(
        project.option(BuildOption.environment(variable)).map { listOf(it) }.orElse(defaultPaths(variable)), false)
    fun hostDirectory(variable: String): Provider<String> = project.option(BuildOption.environment(variable))
        .orElse(project.selectBuildPath(defaultPaths(variable), true))
    private fun xcode(name: String) = project.providers.exec { commandLine("/usr/bin/xcrun", "--find", name) }.standardOutput.asText.map { it.trim() }
    private val sdkGit = if (mac) xcode("git") else project.provider { "/usr/bin/git" }
    val git: InstalledTool by lazy {
        sdk("git", sdkGit, project.providers.exec { commandLine(sdkGit.get(), "--exec-path") }.standardOutput.asText.map {
            mapOf("GIT_EXEC_PATH" to it.trim())
        })
    }
    val bash: InstalledTool by lazy { sdk("bash", project.provider { "/bin/bash" }) }
    val swift: InstalledTool by lazy { sdk("swift", xcode("swift")) }
    companion object {
        fun get(project: Project): NativeBuildTools = project.rootProject.run {
            extensions.findByType(NativeBuildTools::class.java) ?: NativeBuildTools(this).also { extensions.add("dependeasyNativeBuildTools", it) }
        }
    }
}
