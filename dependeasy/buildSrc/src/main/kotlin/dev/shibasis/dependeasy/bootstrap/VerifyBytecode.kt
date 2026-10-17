package dev.shibasis.dependeasy.bootstrap

import dev.shibasis.dependeasy.toolchain.GradlePlugins
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.DataInputStream
import java.util.zip.ZipFile

@DisableCachingByDefault(because = "Verification has no outputs")
abstract class VerifyBytecode : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jars: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val expected = GradlePlugins.BytecodeJava + 44
        var classes = 0
        jars.files.sortedBy { it.name }.forEach { jar ->
            ZipFile(jar).use { archive ->
                archive.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                    DataInputStream(archive.getInputStream(entry)).use { input ->
                        check(input.readInt() == 0xCAFEBABE.toInt()) { "Invalid class in ${jar.name}: ${entry.name}" }
                        input.readUnsignedShort()
                        val actual = input.readUnsignedShort()
                        check(actual == expected) {
                            "${jar.name}: ${entry.name} has bytecode major $actual; expected $expected (Java ${GradlePlugins.BytecodeJava})"
                        }
                        classes++
                    }
                }
            }
        }
        logger.lifecycle("Verified {} classes with Java {} bytecode", classes, GradlePlugins.BytecodeJava)
    }
}

class BytecodeVerificationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("java")
        val verification = project.tasks.register("verifyBytecode", VerifyBytecode::class.java) {
            jars.from(project.tasks.named("jar"))
        }
        project.tasks.named("check") { dependsOn(verification) }
    }
}
