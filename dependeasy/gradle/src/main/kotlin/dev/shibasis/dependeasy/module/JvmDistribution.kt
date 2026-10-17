package dev.shibasis.dependeasy.module

import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.gradle.api.plugins.JavaPlugin
import dev.shibasis.dependeasy.handles.publishOutput
import dev.shibasis.dependeasy.model.OutputKind

internal fun Project.jvmDistribution(name: String, main: String, executable: String) = run {
    val execution = jvmExecution("main")
    val launcher = jvmCommand("generate${name.replaceFirstChar(Char::uppercase)}Launcher", main, "main", provider { emptyList() })
    launcher.configure {
        launcherFile.set(layout.buildDirectory.file("generated/dependeasy/$name/$executable"))
        relativeClasspath.set("lib")
    }
    val kotlin = extensions.findByType(KotlinMultiplatformExtension::class.java)
    val api = if (kotlin == null) JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME
        else kotlin.targets.withType(KotlinJvmTarget::class.java).single().apiElementsConfigurationName
    val artifacts = configurations.named(api).map { it.outgoing.artifacts }
    val jars = files(artifacts.map { entries -> entries.map { it.file } })
        .builtBy(artifacts.map { entries -> entries.map { it.buildDependencies } })
    tasks.register<Sync>(name) {
        group = "distribution"
        from(jars) { into("lib") }
        from(execution.classpath.filter { it.extension == "jar" }) { into("lib") }
        from(launcher.flatMap { it.launcherFile }) { into("bin") }
        into(layout.buildDirectory.dir(name.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()))
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        preserve { include("bin/**") }
    }.also { distribution ->
        publishOutput(OutputKind.JvmDistribution, distribution.map { it.destinationDir }, producer = distribution)
    }
}
