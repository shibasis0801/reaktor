package dev.shibasis.dependeasy.interop

import dev.shibasis.dependeasy.handles.Tools
import dev.shibasis.dependeasy.settings.nativeTools
import dev.shibasis.dependeasy.handles.publishOutput
import dev.shibasis.dependeasy.model.OutputKind
import dev.shibasis.dependeasy.model.Verification
import org.gradle.language.base.plugins.LifecycleBasePlugin
import dev.shibasis.dependeasy.native.CMakeBuild
import dev.shibasis.dependeasy.process.CommandTask
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal fun Project.interopHostTests(name: String, bundleFile: java.io.File, bundle: TaskProvider<CommandTask>) {
    val compile = CMakeBuild(this, "${name}Host").apply {
        source = "."
        target = "interopHostTest"
        inputs.add(rootProject.file("dependeasy/cmake"))
        arguments.add("-DHERMES_BUILD_DIR=${rootDir.resolve("build/dependeasy/tools/hermes").absolutePath}")
    }.register().also { task ->
        task.configure {
            dependsOn(nativeTools.sourceTasks.getValue(Tools.Hermes), nativeTools.hermesCompiler)
            sourceFiles.from(rootProject.fileTree(".github_modules/hermes") { exclude(".git/**", "debug/**", "build/**") })
        }
    }
    val check = tasks.register<CommandTask>("${name}HostCheck") {
        group = "verification"
        backend.set("cmake")
        dependsOn(compile, bundle)
        workingDirectory.set(project.layout.projectDirectory)
        executable.set(compile.flatMap { it.buildDirectory }.map { it.file("interopHostTest").asFile.absolutePath })
        arguments.set(listOf(bundleFile.absolutePath))
        sourceFiles.from(bundleFile, compile.flatMap { it.buildDirectory }.map { it.file("interopHostTest") })
        toolVersion.set("C++${ToolchainVersions.CppStandard} / Hermes ${ToolchainVersions.HermesAndroid}")
        receiptFile.set(project.layout.buildDirectory.file("dependeasy/checks/${name}HostCheck.properties"))
    }
    publishOutput(OutputKind.VerificationResults, check.flatMap { it.receiptFile }, Verification.InteropHost, producer = check)
    tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure { dependsOn(check) }
}
