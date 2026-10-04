import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*

plugins {
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies {
            api("org.jetbrains.kotlinx:atomicfu:0.28.0")
            api(project(":reaktor-core"))
        }
    }
    droid {}
    darwin {}
    web {}
    server {}
}

android {
    defaults("dev.shibasis.reaktor.graph.port")
}

// The JVM test runner scans every class in the test source set, so a plain fixture class
// (no @Test methods) fails with initializationError. Restrict it to test classes by name.
tasks.withType<Test>().configureEach {
    filter {
        isFailOnNoMatchingTests = false
        includeTestsMatching("*Test")
    }
}
