package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.plugins.DependeasyExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

fun Project.dependeasy(configuration: DependeasyExtension.() -> Unit) {
    extensions.configure(DependeasyExtension::class.java, configuration)
}
