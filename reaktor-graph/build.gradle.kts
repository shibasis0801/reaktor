import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.dependencies.useKoin
import org.gradle.api.tasks.testing.Test

plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
    
}
val resilience4jVersion = "2.2.0"

kotlin {
    common {
        dependencies {
            api(project(":reaktor-graph-port"))
            api(project(":reaktor-service"))
            api(project(":reaktor-ui"))
            api(project(":reaktor-db"))
            arrow()
        }
    }
    droid {}
    darwin {}
    web {}
    server {
        dependencies {
            // Spring beans/context for SpringDependencyAdapter. The service Spring router moved to
            // :reaktor-service; the Exposed/Postgres helpers moved to :reaktor-db.
            springWebFlux()
        }
    }
    useKoin()
}

android {
    defaults("dev.shibasis.reaktor.navigation")
}

tasks.withType<Test>().configureEach {
    // Kotlin serialization and graph test helpers generate concrete JVM classes in
    // commonTest. Gradle/JUnit discovery can otherwise try to execute DTOs,
    // serializers, and private helper classes as tests.
    setScanForTestClasses(false)
    include("**/*Test.class", "**/*Tests.class")
    exclude("**/*\$*")
}
