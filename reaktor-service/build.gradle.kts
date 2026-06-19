import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import org.gradle.api.tasks.testing.Test

plugins {
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies {
            // reaktor-io brings reaktor-core (serialization/coroutines/framework/StatusCode) and the
            // Ktor client (`http`) used by the generic Service client. The service layer is graph-neutral.
            api(project(":reaktor-io"))
        }
    }
    droid {}
    darwin {}
    web {}
    server {
        dependencies {
            // Spring WebFlux router (SpringRouter.toRouter/nest) lives in jvmMain.
            springWebFlux()
            api("io.projectreactor.kotlin:reactor-kotlin-extensions:1.2.3")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:${Version.Coroutines}")
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.service")
}

tasks.withType<Test>().configureEach {
    // Serializable request/response DTOs and handler helpers in commonTest are not tests; disable
    // auto-discovery so Gradle/JUnit don't try to execute them.
    setScanForTestClasses(false)
    include("**/*Test.class", "**/*Tests.class")
    exclude("**/*\$*")
}
