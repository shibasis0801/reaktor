rootProject.name = "reaktor-cli"

pluginManagement {
    includeBuild("../dependeasy")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

plugins {
    id("dev.shibasis.dependeasy.settings")
}

// Keep the CLI independently runnable while resolving the shared tooling module from this checkout.
includeBuild("..") {
    dependencySubstitution {
        substitute(module("dev.shibasis:reaktor-tooling")).using(project(":reaktor-tooling"))
        substitute(module("dev.shibasis:reaktor-devtools")).using(project(":reaktor-devtools"))
    }
}
