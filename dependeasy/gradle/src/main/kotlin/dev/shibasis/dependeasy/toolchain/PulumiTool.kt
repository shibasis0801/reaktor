package dev.shibasis.dependeasy.toolchain

import dev.shibasis.dependeasy.tasks.registeredTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal fun Project.pulumiTool(): TaskProvider<InstallTool> = rootProject.run {
    val platform = toolPlatform()
    val version = ToolchainVersions.Pulumi
    val checksums = mapOf(
        "darwin-arm64" to "ef509a84f0ad8f4ed0ec04be957e6688f094eb9a1cdc676a4864103d66d8a4e9",
        "darwin-x64" to "b0c4e37e9fd9573d3e09b65606124486ef2c2049db1237ca6037c4204c6a99a8",
        "linux-arm64" to "47c1b9283b7997337b0a373d240f771a6aeb42ee5dd2e213c14604247c7ff15a",
        "linux-x64" to "903cafeaa9f6327e4bf53a8b7b99ae660f26198acfa804a2f982bf5de3f1de0b",
    )
    registeredTask(InstallTool::class.java, "${InstallTool::class.java.name}:pulumi") { tasks.register<InstallTool>("pulumiSetup") {
        group = "dependeasy"
        url.set("https://github.com/pulumi/pulumi/releases/download/v$version/pulumi-v$version-$platform.tar.gz")
        checksum.set(checksums.getValue(platform))
        archivePrefix.set("pulumi/")
        binaryName.set("pulumi")
        destination.set(gradle.gradleUserHomeDir.resolve("dependeasy/tools/pulumi/$version/$platform/distribution"))
    } }
}
