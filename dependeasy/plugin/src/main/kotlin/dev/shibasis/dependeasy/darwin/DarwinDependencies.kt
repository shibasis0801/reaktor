package dev.shibasis.dependeasy.darwin

import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.utils.installModules
import dev.shibasis.dependeasy.utils.module
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler


fun KotlinDependencyHandler.darwinNetworking(
) = installModules(
    module("io.ktor:ktor-client-darwin:${Version.Ktor}")
)
