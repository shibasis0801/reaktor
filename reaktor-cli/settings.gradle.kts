rootProject.name = "reaktor-cli"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Keep the CLI independently runnable while resolving the shared tooling module from this checkout.
includeBuild("..") {
    dependencySubstitution {
        substitute(module("dev.shibasis:reaktor-tooling")).using(project(":reaktor-tooling"))
    }
}
