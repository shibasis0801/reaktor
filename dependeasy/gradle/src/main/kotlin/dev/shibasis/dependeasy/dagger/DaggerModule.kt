package dev.shibasis.dependeasy.dagger

import dev.shibasis.dependeasy.workspace.hasBuildFile
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import dev.shibasis.dependeasy.model.DaggerToolchainContract
import dev.shibasis.dependeasy.model.AndroidSdkContract
import dev.shibasis.dependeasy.model.BuildModelReader
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.OptionArgument
import dev.shibasis.dependeasy.model.OutputKind

internal class DaggerModule(private val project: Project, options: List<OptionArgument<*>>) {
    private val gradleProperties = options.associate { it.option.name to it.value.toString() }

    internal fun register(): NodeHandle {
        dev.shibasis.dependeasy.toolchain.VerificationTools.get(project).dagger
        fun generation(name: String, output: Any) = project.tasks.register<GenerateDaggerTask>(name) {
            group = "code generation"
            buildName.set(project.rootProject.name)
            engineVersion.set(ToolchainVersions.Dagger)
            gradleProperties.set(this@DaggerModule.gradleProperties)
            executionEnvironment.set(mapOf(
                "PNPM_CONFIG_NETWORK_CONCURRENCY" to "4",
                "PNPM_CONFIG_CHILD_CONCURRENCY" to "1",
            ))
            toolchainContract.set(Json { encodeDefaults = true }.encodeToString(DaggerToolchainContract(
                javaImage = ToolchainVersions.JavaBuildImage,
                browserImage = "mcr.microsoft.com/playwright:v1.64.0-noble",
                gradleUrl = "https://services.gradle.org/distributions/gradle-${ToolchainVersions.Gradle}-bin.zip",
                gradleSha256 = ToolchainVersions.GradleSha256,
                gradleArchive = "/opt/gradle-dist/gradle-bin.zip",
                packages = listOf("git", "curl", "ca-certificates", "unzip", "cmake", "build-essential", "pkg-config",
                    "sqlite3", "libgtk-3-dev", "libwebkit2gtk-4.1-dev", "libarchive-tools", "ninja-build"),
                androidSdk = AndroidSdkContract(
                    directory = "/opt/android-sdk",
                    commandLineTools = ToolchainVersions.AndroidCommandLineTools,
                    downloadUrl = "https://dl.google.com/android/repository/${ToolchainVersions.AndroidCommandLineToolsArchive}",
                    sha256 = ToolchainVersions.AndroidCommandLineToolsSha256,
                    packages = listOf("platform-tools", "platforms/android-${ToolchainVersions.AndroidSdkPlatform}",
                        "build-tools/${ToolchainVersions.AndroidBuildTools}", "ndk/${ToolchainVersions.Ndk}", "cmake/${ToolchainVersions.Cmake}"),
                ),
            )))
            outputFile.set(project.file(output))
        }
        val generated = generation("generateDagger", project.rootProject.layout.projectDirectory.file("dagger/pipeline.json"))
        if (project.hasBuildFile(project.rootDir.resolve("dependeasy/dagger/src/index.ts"))) generated.configure {
            sharedDirectory.set(project.rootProject.layout.projectDirectory.dir("dependeasy/dagger"))
            sharedMetadata.set(mapOf(
                "dagger.json" to buildJsonObject {
                    put("name", "dependeasy"); put("engineVersion", "v" + ToolchainVersions.Dagger)
                    putJsonObject("sdk") { put("source", "typescript") }; put("source", ".")
                }.toString(),
                "package.json" to buildJsonObject {
                    put("name", "dependeasy-dagger"); put("version", "0.0.0"); put("type", "module")
                    put("packageManager", "pnpm@" + ToolchainVersions.Pnpm)
                    putJsonObject("dependencies") { put("typescript", ToolchainVersions.TypeScriptApi) }
                    putJsonObject("devDependencies") { put("typescript-native", "npm:typescript@" + ToolchainVersions.TypeScript) }
                    putJsonObject("scripts") { put("typecheck", "node ../javascript/typecheck.mjs --noEmit") }
                }.toString(),
                "tsconfig.json" to buildJsonObject {
                    putJsonObject("compilerOptions") {
                        put("experimentalDecorators", true); put("target", "ES2022"); put("module", "ESNext"); put("moduleResolution", "Bundler")
                        put("strict", true); put("skipLibCheck", true); put("esModuleInterop", true)
                        putJsonObject("paths") {
                            put("@dagger.io/dagger", JsonArray(listOf(JsonPrimitive("./sdk/index.ts"))))
                            put("@dagger.io/dagger/telemetry", JsonArray(listOf(JsonPrimitive("./sdk/telemetry.ts"))))
                        }
                    }
                }.toString(),
            ))
        }
        return project.node(generated, OutputKind.GeneratedContract)
    }
}
