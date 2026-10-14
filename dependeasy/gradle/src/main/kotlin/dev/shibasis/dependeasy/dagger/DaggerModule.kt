package dev.shibasis.dependeasy.dagger

import dev.shibasis.dependeasy.workspace.BuildTarget
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import dev.shibasis.dependeasy.model.DaggerToolchainContract
import dev.shibasis.dependeasy.model.AndroidSdkContract
import dev.shibasis.dependeasy.model.BuildModelReader
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.model.OutputKind

class DaggerModule internal constructor(private val project: Project, private val targets: Map<String, BuildTarget>) {
    var className = project.name.replaceFirstChar(Char::uppercaseChar)
    var browserImage = "mcr.microsoft.com/playwright:v1.64.0-noble"
    val gradleProperties = linkedMapOf<String, String>()

    fun <T : Any> option(option: BuildOption<T>, value: T) { gradleProperties[option.name] = value.toString() }

    internal fun register(directory: Any): NodeHandle {
        dev.shibasis.dependeasy.toolchain.VerificationTools.get(project).dagger
        fun generation(name: String, output: Any) = project.tasks.register<GenerateDaggerTask>(name) {
            group = "code generation"
            buildName.set(project.name)
            engineVersion.set(ToolchainVersions.Dagger)
            browserImage.set(this@DaggerModule.browserImage)
            gradleProperties.set(this@DaggerModule.gradleProperties)
            executionEnvironment.set(mapOf(
                "GRADLE_OPTS" to "-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=1024m -XX:ReservedCodeCacheSize=128m' -Dorg.gradle.workers.max=1 -Dorg.gradle.vfs.watch=false -Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process -Dorg.gradle.project.kotlin.daemon.jvmargs=-Xmx1024m",
                "NODE_OPTIONS" to "--max-old-space-size=384",
                "PNPM_CONFIG_NETWORK_CONCURRENCY" to "4",
                "PNPM_CONFIG_CHILD_CONCURRENCY" to "1",
            ))
            toolchainContract.set(Json { encodeDefaults = true }.encodeToString(DaggerToolchainContract(
                javaImage = ToolchainVersions.JavaBuildImage,
                gradleUrl = "https://services.gradle.org/distributions/gradle-${ToolchainVersions.Gradle}-bin.zip",
                gradleSha256 = ToolchainVersions.GradleSha256,
                gradleArchive = "/opt/gradle-dist/gradle-bin.zip",
                androidSdk = AndroidSdkContract(
                    directory = "/opt/android-sdk",
                    commandLineTools = ToolchainVersions.AndroidCommandLineTools,
                    downloadUrl = "https://dl.google.com/android/repository/${ToolchainVersions.AndroidCommandLineToolsArchive}",
                    sha256 = ToolchainVersions.AndroidCommandLineToolsSha256,
                    packages = listOf("platform-tools", "platforms/android-${ToolchainVersions.AndroidSdkPlatform}",
                        "build-tools/${ToolchainVersions.AndroidBuildTools}", "ndk/${ToolchainVersions.Ndk}", "cmake/${ToolchainVersions.Cmake}"),
                ),
            )))
            modelFile.set(dev.shibasis.dependeasy.workspace.BuildWorkspace.get(project).graph.flatMap { it.outputFile })
            declarations.set(project.provider { targets.values.map { BuildModelReader.encode(it.model()) } })
            edges.set(project.provider { targets.values.flatMap { it.edges() }.map { BuildModelReader.json.encodeToString(it) } })
            outputFile.set(project.file(output))
        }
        val reference = generation("generateDaggerReference", project.layout.buildDirectory.file("dependeasy/dagger-reference/pipeline.json"))
        val generated = generation("generateDagger", project.layout.projectDirectory.file("dagger/pipeline.json"))
        if (project.rootDir.resolve("dependeasy/dagger/src/index.ts").isFile) generated.configure {
            sharedDirectory.set(project.layout.projectDirectory.dir("dependeasy/dagger"))
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
        val verification = project.tasks.register<VerifyDaggerTask>("verifyDagger") {
            group = "verification"
            mustRunAfter(generated)
            expected.set(reference.flatMap { it.outputFile })
            actual.set(generated.flatMap { it.outputFile })
        }
        dev.shibasis.dependeasy.workspace.BuildWorkspace.get(project).graph.configure {
            taskFamilies.put(":" + generated.name, "metadata")
            taskFamilies.put(":" + verification.name, "metadata")
        }
        return project.node(verification, OutputKind.VerificationResults)
    }
}
