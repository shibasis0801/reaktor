package dev.shibasis.dependeasy.native

import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.handles.publishOutput
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.tasks.CmakePlatform
import dev.shibasis.dependeasy.tasks.kotlinCmake
import dev.shibasis.dependeasy.toolchain.NativeBuildTools
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

internal fun Project.nativeHostTests(native: NativeLibrary): dev.shibasis.dependeasy.handles.NodeHandle {
    val platform = CmakePlatform.Host()
    val directory = nativeBuildDirectory(platform.variant)
    val results = layout.buildDirectory.file("dependeasy/checks/nativeHostCheck.xml")
    val compiler = requireNotNull(kotlinCmake(platform)) { "Native tests need a host platform" }
    compiler.configure { buildTarget.set(CMake.All) }
    val cmake = NativeBuildTools.get(this).cmake
    val names = native.testExecutables.map { it.name }
    val verification = tasks.register<ProcessTask>("nativeHostCheck") {
        group = "verification"
        executionEffect.set(Effect.Verify)
        dependsOn(compiler)
        workingDirectory.set(layout.projectDirectory)
        executable.set(cmake.executable.map { it.asFile.parentFile.resolve("ctest").absolutePath })
        arguments.set(results.map { CMake.tests(directory.absolutePath, it.asFile.absolutePath) })
        outputs.file(results)
        toolVersion.set(cmake.handle.version)
        sourceFiles.from(compiler.flatMap { it.nativeModelFile }, compiler.flatMap { it.buildDirectory }.map { directory ->
            names.map { directory.file(it) } + directory.file("CTestTestfile.cmake")
        })
        receiptFile.set(layout.buildDirectory.file("dependeasy/checks/nativeHostCheck.properties"))
    }
    publishOutput(OutputKind.VerificationResults, results, Verification.NativeHost, producer = verification)
    return node(verification).after(node(compiler))
}
