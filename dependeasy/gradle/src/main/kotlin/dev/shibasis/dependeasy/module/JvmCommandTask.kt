package dev.shibasis.dependeasy.module

import dev.shibasis.dependeasy.Versions
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Prints a launch command with paths from this checkout")
abstract class JvmCommandTask : DefaultTask() {
    @get:Classpath abstract val classpath: ConfigurableFileCollection
    @get:Input abstract val executable: Property<String>
    @get:Input abstract val mainClass: Property<String>
    @get:Input abstract val arguments: ListProperty<String>
    @get:OutputFile @get:Optional abstract val launcherFile: RegularFileProperty
    @get:Input @get:Optional abstract val relativeClasspath: Property<String>

    @TaskAction fun printCommand() {
        val cp = if (relativeClasspath.isPresent) "\"\$here/${relativeClasspath.get()}/*\"" else quote(classpath.asPath)
        val command = "${quote(executable.get())} -cp $cp " +
            (listOf(mainClass.get()) + arguments.get()).joinToString(" ", transform = ::quote)
        if (launcherFile.isPresent) launcherFile.get().asFile.apply {
            parentFile.mkdirs()
            val base = if (relativeClasspath.isPresent) "here=\$(cd \"\$(dirname \"\$0\")/..\" && pwd)\n" else ""
            writeText("#!/bin/sh\n${base}exec $command \"\$@\"\n")
            check(setExecutable(true)) { "Could not enable launcher $this" }
        } else println(command)
    }

    private fun quote(value: String) = "'${value.replace("'", "'\"'\"'")}'"
}

internal fun Project.jvmCommand(name: String, main: String, compilation: String, arguments: Provider<List<String>>): org.gradle.api.tasks.TaskProvider<JvmCommandTask> {
    val execution = jvmExecution(compilation)
    val launcher = extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(Versions.SDK.Java.asInt))
    }
    return tasks.register<JvmCommandTask>(name) {
        group = "application"
        dependsOn(execution.classes)
        classpath.from(execution.classpath)
        mainClass.set(main)
        this.arguments.set(arguments)
        executable.set(launcher.map { it.executablePath.asFile.absolutePath })
    }
}
