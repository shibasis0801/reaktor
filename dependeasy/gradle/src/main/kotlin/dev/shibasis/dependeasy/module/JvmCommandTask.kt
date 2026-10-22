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
abstract class JvmProcessTask : DefaultTask() {
    @get:Classpath abstract val classpath: ConfigurableFileCollection
    @get:Input abstract val executable: Property<String>
    @get:Input abstract val mainClass: Property<String>
    @get:Input abstract val arguments: ListProperty<String>
    @get:Input abstract val jvmArguments: ListProperty<String>
    @get:OutputFile @get:Optional abstract val launcherFile: RegularFileProperty
    @get:Input @get:Optional abstract val relativeClasspath: Property<String>

    @TaskAction fun printCommand() {
        val command = dev.shibasis.dependeasy.model.JvmLaunchKernel.command(executable.get(), classpath.asPath,
            mainClass.get(), arguments.get(), jvmArguments.get(), relativeClasspath.orNull)
        val output = launcherFile.orNull?.asFile
        if (output?.extension == "jar") {
            require(arguments.get().isEmpty()) { "An archive launcher accepts its arguments at invocation" }
            dev.shibasis.dependeasy.model.JvmLaunchKernel.archive(mainClass.get(),
                classpath.files.filter { it.extension == "jar" }, relativeClasspath.get(), jvmArguments.get(), output)
        } else if (output != null) dev.shibasis.dependeasy.model.JvmLaunchKernel.launcher(
            command, relativeClasspath.isPresent, output) else println(command)
    }
}

internal fun Project.jvmCommand(name: String, main: Provider<String>, compilation: String, arguments: Provider<List<String>>): org.gradle.api.tasks.TaskProvider<JvmProcessTask> {
    val project = this
    val launcher = extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(Versions.SDK.Java.asInt))
    }
    return tasks.register<JvmProcessTask>(name) {
        val execution = project.jvmExecution(compilation)
        group = "application"
        dependsOn(execution.classes)
        classpath.from(execution.classpath)
        mainClass.set(main)
        this.arguments.set(arguments)
        jvmArguments.convention(emptyList())
        executable.set(launcher.map { it.executablePath.asFile.absolutePath })
    }
}
