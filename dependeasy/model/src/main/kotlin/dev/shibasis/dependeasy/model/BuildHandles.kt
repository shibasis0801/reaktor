package dev.shibasis.dependeasy.model

import java.io.Serializable as JavaSerializable
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

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
    DatabaseMigration("database"), BrowserBundle("web"), JavaScriptInstallation("web"), PublishedModules("publication"), WorkspaceCompletion("verification"), DeclaredBuildModel("verification")
}
enum class Publication { Local, Remote }
@Serializable enum class Runner {
    @SerialName("gradle") Gradle,
    @SerialName("pnpm") Pnpm,
    @SerialName("fastlane") Fastlane,
    @SerialName("browser") Browser,
    @SerialName("k6") K6,
    @SerialName("karate") Karate, @SerialName("shell") Shell,
}
@Serializable @JvmInline value class Environment(val name: String) : JavaSerializable
@Serializable data class WorkerRef(val name: String, val directory: String) : JavaSerializable
@Serializable @JvmInline value class ServiceRef(val name: String) : JavaSerializable
@Serializable data class LaneHandle(val platform: String, val name: String) : JavaSerializable
@Serializable data class PackageScript(val name: String, val directory: String = ".") : JavaSerializable
@Serializable sealed interface ExternalCall {
    @Serializable data class Fastlane(val lane: LaneHandle) : ExternalCall
    @Serializable data class Package(val script: PackageScript) : ExternalCall
    @Serializable data class BashScript(val path: String) : ExternalCall
}
object IosLane {
    val BuildDebug = LaneHandle("ios", "build_debug")
    val BuildRelease = LaneHandle("ios", "build_release")
    val TestflightInternal = LaneHandle("ios", "testflight_internal")
}
object AndroidLane { val BuildRelease = LaneHandle("android", "build_release") }

data class ToolHandle(val name: String, val version: String) : JavaSerializable

@Serializable
@JvmInline
value class CompilationRef(val name: String) : JavaSerializable {
    companion object {
        val Main = CompilationRef("main")
        val Test = CompilationRef("test")
    }
}

@Serializable
@JvmInline
value class SourceSetRef(val name: String) : JavaSerializable {
    companion object {
        val Main = SourceSetRef("main")
        val CommonMain = SourceSetRef("commonMain")
        val Test = SourceSetRef("test")
    }
}

@Serializable
@JvmInline
value class ConfigurationRef(val name: String) : JavaSerializable {
    companion object {
        val JvmRuntimeClasspath = ConfigurationRef("jvmRuntimeClasspath")
        val RuntimeClasspath = ConfigurationRef("runtimeClasspath")
        val JavaScriptCompileClasspath = ConfigurationRef("jsCompileClasspath")
    }
}
