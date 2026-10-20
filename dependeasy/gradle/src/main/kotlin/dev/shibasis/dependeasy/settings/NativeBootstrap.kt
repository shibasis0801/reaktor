package dev.shibasis.dependeasy.settings

import dev.shibasis.dependeasy.toolchain.buildHost
import dev.shibasis.dependeasy.handles.Tools
import dev.shibasis.dependeasy.model.ToolHandle
import org.gradle.api.tasks.TaskProvider
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.declarationSources
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.NodeKind
import dev.shibasis.dependeasy.tasks.KotlinCMakeTask
import dev.shibasis.dependeasy.tasks.CmakePlatform
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import dev.shibasis.dependeasy.native.Native

/** Registers optional native preparation. Settings evaluation performs no I/O or builds. */
internal class NativeBootstrap(private val project: Project) {
    val sourceTasks = linkedMapOf<ToolHandle, TaskProvider<GitSourceTask>>()
    lateinit var hermesCompiler: TaskProvider<KotlinCMakeTask>
        private set
    fun register() = with(project) {
        if (rootDir.resolve("dependeasy/gradle/src/main/kotlin/dev/shibasis/dependeasy/toolchain/NativeSources.kt").absolutePath !in declarationSources(rootDir)) return@with
        val sources = Native.sources.associate { it.source!! to it }.mapValues { (tool, source) ->
            val name = tool.name
            tasks.register<GitSourceTask>("prepare${name.replaceFirstChar(Char::uppercaseChar)}Source") {
                group = "dependeasy"
                repository.set(source.repository)
                revision.set(source.revision)
                checkout.set(layout.projectDirectory.dir(".github_modules/$name"))
            }
        }
        sourceTasks.putAll(sources)
        val hermes = sources.getValue(Tools.Hermes)
        val compiler = tasks.register<KotlinCMakeTask>("buildHermesHostCompiler") {
            group = "dependeasy"
            dependsOn(hermes)
            sourceDirectory.set(hermes.flatMap { it.checkout })
            sourceFiles.from(hermes.flatMap { it.checkout }.map { it.asFileTree.matching { exclude(".git/**", "debug/**", "build/**") } })
            buildDirectory.set(layout.buildDirectory.dir("dependeasy/tools/hermes"))
            generator.set("Ninja")
            buildTarget.set("hermesc")
            val mac = project.buildHost.mac
            toolVersion.set(dev.shibasis.dependeasy.toolchain.NativeBuildTools.get(project).let {
                "cmake ${it.cmake.handle.version} / ninja ${it.ninja.handle.version}"
            })
            if (mac) {
                val sdk = providers.exec { commandLine("/usr/bin/xcrun", "--sdk", "macosx", "--show-sdk-path") }.standardOutput.asText.map { it.trim() }
                environment.put("SDKROOT", sdk)
                configureArguments.add(sdk.map { "-DCMAKE_OSX_SYSROOT=$it" })
                configureArguments.add("-DCMAKE_OSX_DEPLOYMENT_TARGET=${dev.shibasis.dependeasy.Versions.SDK.targetMacos}")
            }
            configureArguments.addAll(listOf(
                "-DCMAKE_BUILD_TYPE=Release", "-DHERMES_BUILD_APPLE_FRAMEWORK=OFF",
                "-DHERMES_ENABLE_TEST_SUITE=OFF", "-DHERMES_ENABLE_DEBUGGER=OFF",
            ))
        }
        hermesCompiler = compiler
        BuildWorkspace.get(this).aggregate(this, "prepareNativeTools", NodeKind.ArtifactSet,
            listOf(node(compiler).after(node(hermes)), node(sources.getValue(Tools.Flatbuffers))))
    }
}

internal val Project.nativeTools get() = rootProject.extensions.getByType(NativeBootstrap::class.java)
