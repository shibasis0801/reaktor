import dev.shibasis.dependeasy.Versions

import dev.shibasis.dependeasy.common.arrow
import dev.shibasis.dependeasy.server.springWebFlux

import dev.shibasis.dependeasy.dependencies.useKoin
import org.gradle.api.tasks.testing.Test

plugins { id("dev.shibasis.dependeasy.compose-library") }

dependeasy {
    module("dev.shibasis.reaktor.navigation") {
        common {
            dependencies {
                api(project(":reaktor-graph-runtime"))
                api(project(":reaktor-ui"))
                arrow()
            }
        }
        android {}
        apple {}

        web {
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
