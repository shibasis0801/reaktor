package dev.shibasis.dependeasy.settings

import dev.shibasis.dependeasy.dag.BuildPipeline
import dev.shibasis.dependeasy.tasks.KotlinCMakeTask
import dev.shibasis.dependeasy.tasks.CmakePlatform
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import dev.shibasis.dependeasy.toolchain.NativeSources

/** Registers optional native preparation. Settings evaluation performs no I/O or builds. */
internal class NativeBootstrap(private val project: Project) {
    fun register() = with(project) {
        if (!rootDir.resolve("dependeasy/src/main/kotlin/dev/shibasis/dependeasy/toolchain/NativeSources.kt").isFile) return@with
        val pipeline = BuildPipeline(this, "nativeTools")
        val sources = mapOf("flatbuffers" to NativeSources.flatbuffers, "hermes" to NativeSources.hermes).mapValues { (name, source) ->
            tasks.register<GitSourceTask>("prepare${name.replaceFirstChar(Char::uppercaseChar)}Source") {
                group = "dependeasy"
                repository.set(source.first)
                revision.set(source.second)
                checkout.set(layout.projectDirectory.dir(".github_modules/$name"))
            }
        }
        val hermes = sources.getValue("hermes")
        val compiler = tasks.register<KotlinCMakeTask>("buildHermesHostCompiler") {
            group = "dependeasy"
            dependsOn(hermes)
            sourceDirectory.set(hermes.flatMap { it.checkout })
            sourceFiles.from(fileTree(".github_modules/hermes") {
                exclude(".git/**", "debug/**", "build/**")
            })
            buildDirectory.set(layout.buildDirectory.dir("dependeasy/tools/hermes"))
            generator.set("Ninja")
            buildTarget.set("hermesc")
            val mac = providers.systemProperty("os.name").get().contains("Mac", ignoreCase = true)
            val cmake = if (mac) CmakePlatform.Darwin("macosx").cmakeExecutable else "cmake"
            cmakeExecutable.set(cmake)
            val compilerCommand = if (mac) listOf("xcrun", "--sdk", "macosx", "clang++", "--version")
                else listOf("c++", "--version")
            toolVersion.set(providers.exec { commandLine(cmake, "--version") }.standardOutput.asText
                .zip(providers.exec {
                    commandLine(compilerCommand)
                }.standardOutput.asText) { cmake, compiler -> "${cmake.trim()} / ${compiler.trim()}" })
            if (mac) {
                val ninja = listOf("/opt/homebrew/bin/ninja", "/usr/local/bin/ninja")
                    .firstOrNull { file(it).canExecute() } ?: "ninja"
                configureArguments.add("-DCMAKE_MAKE_PROGRAM=$ninja")
                val sdk = providers.exec { commandLine("xcrun", "--sdk", "macosx", "--show-sdk-path") }.standardOutput.asText.map { it.trim() }
                environment.put("SDKROOT", sdk)
                configureArguments.add(sdk.map { "-DCMAKE_OSX_SYSROOT=$it" })
                configureArguments.add("-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0")
            }
            configureArguments.addAll(listOf(
                "-DCMAKE_BUILD_TYPE=Release", "-DHERMES_BUILD_APPLE_FRAMEWORK=OFF",
                "-DHERMES_ENABLE_TEST_SUITE=OFF", "-DHERMES_ENABLE_DEBUGGER=OFF",
            ))
        }
        pipeline.target("prepareNativeTools", pipeline.node(compiler).after(pipeline.node(hermes)), pipeline.node(sources.getValue("flatbuffers")))
    }
}
