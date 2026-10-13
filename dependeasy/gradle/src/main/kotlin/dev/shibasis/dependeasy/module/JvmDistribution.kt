package dev.shibasis.dependeasy.module

import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

internal fun Project.jvmDistribution(name: String, main: String, executable: String) = run {
    val execution = jvmExecution("main")
    val launcher = jvmCommand("generate${name.replaceFirstChar(Char::uppercase)}Launcher", main, "main", provider { emptyList() })
    launcher.configure {
        launcherFile.set(layout.buildDirectory.file("generated/dependeasy/$name/$executable"))
        relativeClasspath.set("lib")
    }
    val jar = if (extensions.findByType(KotlinMultiplatformExtension::class.java) != null) "jvmJar" else "jar"
    tasks.register<Sync>(name) {
        group = "distribution"
        from(tasks.named(jar)) { into("lib") }
        from(execution.classpath.filter { it.extension == "jar" }) { into("lib") }
        from(launcher.flatMap { it.launcherFile }) { into("bin") }
        into(layout.buildDirectory.dir(name.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()))
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        preserve { include("bin/**") }
    }
}
