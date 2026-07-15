import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*

plugins {
    id("dev.shibasis.dependeasy.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    common {
        dependencies {
            api(project(":compose-flow"))
            api(project(":reaktor-graph"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
        }
    }

    web {
        dependencies {
            kotlinWrappers()
            react()
            webCoroutines()
        }
    }

    droid {
        dependencies {
            activityFragment()
            androidCoroutines()
            extensions()
        }
    }

    darwin {
        dependencies {
            api("org.jetbrains.kotlinx:atomicfu:0.23.1")
        }
    }

    server {
        dependencies {
            api(compose.desktop.currentOs)
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.flow")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // Match the reaktorDesktop convention: only top-level *Test classes are test classes.
    // Without the exclude, Gradle's scanner reports nested helpers (Companion, private data
    // classes) of a test file as bogus test classes failing with initializationError.
    include("**/*Test.class")
    exclude("**/*\$*")
}
