package dev.shibasis.dependeasy.interop

import dev.shibasis.dependeasy.tasks.KotlinCMakeTask
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

internal fun Project.nativeExports(name: String, installers: Map<String, String>) {
    if (installers.isEmpty()) return
    val modules = installers.toMap()
    val generation = tasks.register<NativeExportsTask>("generate${name.replaceFirstChar(Char::uppercaseChar)}NativeExports") {
        group = "code generation"
        this.installers.set(modules)
        header.set(layout.buildDirectory.file("generated/dependeasy/$name/cpp/NativeExports.hpp"))
    }
    tasks.withType(KotlinCMakeTask::class.java).configureEach {
        dependsOn(generation)
        sourceFiles.from(generation.flatMap { it.header })
        configureArguments.add(generation.flatMap { it.header }.map { "-DREAKTOR_INTEROP_EXPORTS_HEADER=${it.asFile.absolutePath}" })
    }
}
