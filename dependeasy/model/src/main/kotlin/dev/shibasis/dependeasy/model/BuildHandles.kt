package dev.shibasis.dependeasy.model

import java.io.Serializable as JavaSerializable
import kotlinx.serialization.Serializable

data class BuildHandle(val name: String, val group: String, val module: String = name) : JavaSerializable {
    val coordinate get() = "$group:$module"
}

data class ModuleHandle(val build: BuildHandle, val name: String, val group: String = build.group) : JavaSerializable {
    val path get() = ":$name"
    val coordinate get() = "$group:$name"
}

enum class Workspace { Verify, Build, Android, Apple, Web, Deploy }
enum class Build { Debug, Release }
enum class Apple { IosArm64, IosSimulatorArm64 }
enum class Verification { Jvm, JavaScript, TypeScript, InteropTypeScript, InteropHost, WebBridge, Hangar, Window, KernelBoundary, Plugin }
enum class OutputKind(val family: String, val platform: HostPlatform = HostPlatform.Portable) {
    GeneratedContract("metadata"), KotlinJsLibrary("web"), AppleXcodeFramework("apple", HostPlatform.Macos), AppleFramework("apple", HostPlatform.Macos), AndroidApk("android"),
    AndroidBundle("android"), AndroidLibrary("android"), AndroidInstrumentedTests("android"),
    JvmDistribution("jvm"), WorkerBundle("web"), VerificationResults("verification"),
    ModuleBuild("verification"), NativeLibrary("apple", HostPlatform.Macos), AppleCompilation("apple", HostPlatform.Macos),
    BrowserBundle("web"), JavaScriptInstallation("web"), PublishedModules("publication"), WorkspaceCompletion("verification"), DeclaredBuildModel("verification")
}
enum class Publication { Local, Remote }
enum class Runner { Gradle, Pnpm, Fastlane, Browser, K6, Karate }
@Serializable enum class OptionType { Boolean, String, Integer }
@Serializable enum class OptionSource { CommandLine, SystemProperty, UserGradleProperties, RootGradleProperties, Environment, LocalProperties, Default, XcodeRelease }

data class BuildOption<T : Any>(val name: String, val type: OptionType, val default: T,
    val environment: String? = null,
    val resolutionOrder: List<OptionSource> = listOf(OptionSource.CommandLine, OptionSource.SystemProperty,
        OptionSource.UserGradleProperties, OptionSource.RootGradleProperties, OptionSource.Environment,
        OptionSource.LocalProperties, OptionSource.Default)) : JavaSerializable {
    @Suppress("UNCHECKED_CAST") fun parse(value: String): T = when (type) {
        OptionType.Boolean -> value.toBooleanStrict()
        OptionType.String -> value
        OptionType.Integer -> value.toInt()
    } as T
    companion object {
        fun boolean(name: String, default: Boolean = false) = BuildOption(name, OptionType.Boolean, default)
        fun string(name: String, default: String = "", environment: String? = null) = BuildOption(name, OptionType.String, default, environment)
        fun integer(name: String, default: Int, environment: String? = null) = BuildOption(name, OptionType.Integer, default, environment)
    }
}

@Serializable data class OptionValue(val name: String, val type: OptionType, val value: String,
    val source: OptionSource, val file: String? = null)
@Serializable data class OptionRequirement(val option: OptionValue, val expected: String)

data class Environment(val name: String) : JavaSerializable
data class LaneHandle(val platform: String, val name: String) : JavaSerializable
object IosLane {
    val BuildDebug = LaneHandle("ios", "build_debug")
    val BuildRelease = LaneHandle("ios", "build_release")
    val TestflightInternal = LaneHandle("ios", "testflight_internal")
}
object AndroidLane { val BuildRelease = LaneHandle("android", "build_release") }

data class ToolHandle(val name: String, val version: String) : JavaSerializable
