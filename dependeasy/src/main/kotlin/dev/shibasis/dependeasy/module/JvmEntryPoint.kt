package dev.shibasis.dependeasy.module

import dev.shibasis.dependeasy.Versions
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

internal fun Project.jvmEntryPoint(name: String, main: String, compilation: String,
                                   verify: Boolean, configuration: JavaExec.() -> Unit): TaskProvider<JavaExec> {
    val execution = jvmExecution(compilation)
    val launcher = extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(Versions.SDK.Java.asInt))
    }
    return tasks.register<JavaExec>(name) {
        group = if (verify) "verification" else "application"
        mainClass.set(main)
        javaLauncher.set(launcher)
        classpath = execution.classpath
        dependsOn(execution.classes)
        configuration()
    }.also { task -> if (verify) tasks.named("check") { dependsOn(task) } }
}
