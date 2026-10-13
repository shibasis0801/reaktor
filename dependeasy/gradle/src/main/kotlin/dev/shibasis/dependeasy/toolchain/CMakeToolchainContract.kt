package dev.shibasis.dependeasy.toolchain

import dev.shibasis.dependeasy.tasks.registeredTask
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider

@CacheableTask
abstract class GenerateCMakeToolchain : DefaultTask() {
    @get:Input abstract val cppStandard: Property<Int>
    @get:OutputFile abstract val contract: RegularFileProperty

    @TaskAction fun generate() {
        contract.get().asFile.apply {
            parentFile.mkdirs()
            writeText("set(DEPE_TOOLCHAIN_SCHEMA 1)\nset(CMAKE_CXX_STANDARD ${cppStandard.get()} CACHE STRING \"\" FORCE)\n")
        }
    }
}

internal fun Project.cmakeToolchainContract(): TaskProvider<GenerateCMakeToolchain> {
    val root = rootProject
    val name = "generateCMakeToolchain"
    return root.registeredTask(GenerateCMakeToolchain::class.java) { root.tasks.register(name, GenerateCMakeToolchain::class.java) {
        cppStandard.set(ToolchainVersions.CppStandard.toInt())
        contract.set(root.layout.buildDirectory.file("dependeasy/contracts/toolchain.cmake"))
    } }
}
