package dev.shibasis.dependeasy.dagger

import dev.shibasis.dependeasy.workspace.BuildTarget
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

internal class DaggerModule(private val project: Project, private val targets: Map<String, BuildTarget>,
    options: List<OptionArgument<*>>) {
    private val gradleProperties = options.associate { it.option.name to it.value.toString() }

    internal fun register(): NodeHandle {
        dev.shibasis.dependeasy.toolchain.VerificationTools.get(project).dagger
        fun generation(name: String, output: Any) = project.tasks.register<GenerateDaggerTask>(name) {
            group = "code generation"
            buildName.set(project.rootProject.name)
            engineVersion.set(ToolchainVersions.Dagger)
            browserImage.set("mcr.microsoft.com/playwright:v1.64.0-noble")
            gradleProperties.set(this@DaggerModule.gradleProperties)
            executionEnvironment.set(mapOf(
                "DEPE_CONTAINER_PLATFORM" to "linux/arm64",
                "GRADLE_OPTS" to "-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs='-Xmx768m -XX:NewRatio=5 -XX:MaxMetaspaceSize=768m -XX:ReservedCodeCacheSize=96m -XX:+UseSerialGC -Dkotlin.daemon.options=autoshutdownIdleSeconds=5' -Dorg.gradle.workers.max=1 -Dorg.gradle.parallel=false -Dorg.gradle.vfs.watch=false -Dorg.gradle.project.kotlin.compiler.execution.strategy=daemon -Dorg.gradle.project.kotlin.daemon.useFallbackStrategy=false -Dorg.gradle.project.kotlin.daemon.jvmargs='-Xmx2560m -XX:NewRatio=5 -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=96m -XX:+UseSerialGC' -Dorg.gradle.project.kotlin.mpp.commonizerJvmArgs='-Xmx512m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m -XX:+UseSerialGC'",
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
            declarations.set(project.provider { targets.values.map { BuildModelReader.encode(it.model(project)) } })
            edges.set(project.provider { targets.values.flatMap { it.edges() }.map { BuildModelReader.json.encodeToString(it) } })
            outputFile.set(project.file(output))
        }
        val reference = generation("generateDaggerReference", project.layout.buildDirectory.file("dependeasy/dagger-reference/pipeline.json"))
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
        val verification = project.tasks.register<VerifyDaggerTask>("verifyDagger") {
            group = "verification"
            mustRunAfter(generated)
            expected.set(reference.flatMap { it.outputFile })
            actual.set(project.rootProject.layout.projectDirectory.file("dagger/pipeline.json"))
        }
        project.node(generated, OutputKind.GeneratedContract)
        return project.node(verification, OutputKind.VerificationResults).also {
            dev.shibasis.dependeasy.workspace.BuildWorkspace.get(project)
                .reference(project, verification, OutputKind.VerificationResults, family = "metadata")
        }
    }
}
