package dev.shibasis.dependeasy.module

import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.gradle.api.plugins.JavaPlugin
import dev.shibasis.dependeasy.handles.publishOutput
import dev.shibasis.dependeasy.handles.NodeHandle
import dev.shibasis.dependeasy.handles.KernelArtifact
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.KernelSpec
import dev.shibasis.dependeasy.model.OutputKind

internal fun Project.jvmLauncher(name: String, program: NodeHandle, output: String,
    arguments: List<String> = emptyList(), relativeClasspath: String? = null): org.gradle.api.tasks.TaskProvider<JvmProcessTask> {
    require(program.id.build == rootProject.name && program.id.project == path) {
        "A program launcher belongs to its declaring module"
    }
    val entry = requireNotNull(program.programEntry) { "A launcher needs a declared JVM program" }
    val compilation = requireNotNull(program.programCompilation)
    val launcher = jvmCommand(name, entry.entryClass, compilation.name, provider { arguments })
    launcher.configure {
        jvmArguments.set(dev.shibasis.dependeasy.model.JavaTool.nativeAccess(requireNotNull(program.programEnvironment).nativeAccess))
        launcherFile.set(layout.buildDirectory.file(output))
        relativeClasspath?.let { this.relativeClasspath.set(it) }
        extensions.add(KernelSpec::class.java, "dependeasyKernel", KernelSpec.JvmLauncher(
            program.id, compilation, entry.producer.id, output, arguments, relativeClasspath,
            dev.shibasis.dependeasy.model.ToolRef("java", dev.shibasis.dependeasy.Versions.SDK.Java.asInt.toString())))
    }
    node(launcher).consumes(entry)
    return launcher
}

internal fun Project.jvmDistribution(name: String, program: NodeHandle, executable: String): NodeHandle {
    val compilation = requireNotNull(program.programCompilation) { "A distribution needs a declared JVM program" }
    val entry = requireNotNull(program.programEntry)
    val execution = jvmExecution(compilation.name)
    val launcher = jvmLauncher("generate${name.replaceFirstChar(Char::uppercase)}Launcher", program,
        "generated/dependeasy/$name/$executable", relativeClasspath = "lib")
    val kotlin = extensions.findByType(KotlinMultiplatformExtension::class.java)
    val api = if (kotlin == null) JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME
        else kotlin.targets.withType(KotlinJvmTarget::class.java).single().apiElementsConfigurationName
    val artifacts = configurations.named(api).map { it.outgoing.artifacts }
    val jars = files(artifacts.map { entries -> entries.map { it.file } })
        .builtBy(artifacts.map { entries -> entries.map { it.buildDependencies } })
    val distribution = tasks.register<Sync>(name) {
        group = "distribution"
        from(jars) { into("lib") }
        from(execution.classpath.filter { it.extension == "jar" }) { into("lib") }
        from(launcher.flatMap { it.launcherFile }) { into("bin") }
        into(layout.buildDirectory.dir(name.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()))
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        preserve { include("bin/**") }
        extensions.add(KernelSpec::class.java, "dependeasyKernel",
            KernelSpec.JvmDistribution(program.id, compilation, entry.producer.id, executable))
    }
    publishOutput(OutputKind.JvmDistribution, distribution.map { it.destinationDir }, producer = distribution)
    return node(distribution).consumes(KernelArtifact(launcher.flatMap { it.launcherFile }, node(launcher)))
}
