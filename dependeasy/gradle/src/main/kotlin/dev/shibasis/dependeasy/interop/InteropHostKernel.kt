package dev.shibasis.dependeasy.interop

import dev.shibasis.dependeasy.handles.publishOutput
import dev.shibasis.dependeasy.model.Effect
import dev.shibasis.dependeasy.model.OutputKind
import dev.shibasis.dependeasy.model.Verification
import org.gradle.language.base.plugins.LifecycleBasePlugin
import dev.shibasis.dependeasy.tasks.kotlinCmake
import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal fun Project.interopHostTests(name: String, bundleFile: java.io.File, bundle: TaskProvider<out ProcessTask>): TaskProvider<ProcessTask> {
    val compile = checkNotNull(project.kotlinCmake(dev.shibasis.dependeasy.tasks.CmakePlatform.Host())).also { task ->
        task.configure { buildTarget.set("interopHostTest") }
    }
    val check = tasks.register<ProcessTask>("${name}HostCheck") {
        group = "verification"
        executionEffect.set(Effect.Verify)
        dependsOn(compile, bundle)
        workingDirectory.set(project.layout.projectDirectory)
        executable.set(compile.map { it.buildDirectory.get().file("interopHostTest").asFile.absolutePath })
        arguments.set(listOf(bundleFile.absolutePath))
        sourceFiles.from(bundleFile, compile.map { it.buildDirectory.get().file("interopHostTest") })
        toolVersion.set("C++${ToolchainVersions.CppStandard} / Hermes ${ToolchainVersions.HermesAndroid}")
        receiptFile.set(project.layout.buildDirectory.file("dependeasy/checks/${name}HostCheck.properties"))
    }
    publishOutput(OutputKind.VerificationResults, check.flatMap { it.receiptFile }, Verification.InteropHost, producer = check)
    tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure { dependsOn(check) }
    return check
}
