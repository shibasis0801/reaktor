import dev.shibasis.dependeasy.Versions

import dev.shibasis.dependeasy.common.arrow
import dev.shibasis.dependeasy.server.springWebFlux

import dev.shibasis.dependeasy.dependencies.useKoin
import org.gradle.api.tasks.testing.Test

plugins { id("dev.shibasis.dependeasy.compose-library") }

dependeasy {
    val bindings = javascript("karakum", "ts").bindings("karakum.config.json", "import")
    dag("bindings") { target("generateBindings", node(bindings)) }

    module("dev.shibasis.reaktor.navigation") {
        common {
            dependencies {
                api(projects.reaktorGraphRuntime)
                api(projects.reaktorUi)
                arrow()
            }
        }
        android {}
        apple {}

        web {
            sourceSetModifier { kotlin.srcDir(bindings) }
            dependencies {
                api(Versions.Compose.NavigationEvent)
            }
        }

        jvm {
            dependencies {
                // Spring beans/context for SpringDependencyAdapter. The service Spring router moved to
                // :reaktor-service; the Exposed/Postgres helpers moved to :reaktor-db.
                springWebFlux()
            }
        }

        kotlin {
            useKoin()
        }
    }


    tasks.withType<Test>().configureEach {
        // Kotlin serialization and graph test helpers generate concrete JVM classes in
        // commonTest. Gradle/JUnit discovery can otherwise try to execute DTOs,
        // serializers, and private helper classes as tests.
        exclude("**/*\$*")
    }

    testClasses()
}
