import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.*

import org.gradle.api.tasks.Exec

plugins { id("dev.shibasis.dependeasy.compose-library") }

dependeasy {
    val bindings = javascript("karakum", "ts").bindings("karakum.config.json", "import")
    workspace { val generateBindings by artifacts { include(node(bindings)) } }

    module("dev.shibasis.reaktor.auth") {
        common {
            dependencies {
                api(projects.reaktorAuthCore)
                api(projects.reaktorUi)
                api(projects.reaktorGraph)
                api(projects.reaktorService)
                api(projects.reaktorDb)
                api(projects.reaktorIo)
                api(Versions.Data.Jwt)
            }
        }
        web { sourceSetModifier { kotlin.srcDir(bindings) } }

        android {
            dependencies {
                implementation(Versions.Android.Credentials)
                // Android 13 and below.
                implementation(Versions.Android.CredentialsPlayServices)
                implementation(Versions.Android.GoogleId)
            }
        }
        apple {}

        jvm {
            dependencies {
                api(projects.reaktorSecurity)
                api(projects.reaktorTooling)
                api(Versions.Server.OAuth)
                api(Versions.Server.Security)
                api(Versions.Data.Exposed)
                api(Versions.Data.ExposedJdbc)
                api(Versions.Data.ExposedJson)
                api(Versions.Data.Postgres)
                api(Versions.Kotlin.Reflect)
            }
            testDependencies {
                implementation(Versions.Data.H2)
            }
        }
    }


    tasks.withType<Test> {
        // Shared in-memory DB harnesses, stubs, and Kotlin file facades are test support, not runnable tests.
        filter {
            excludeTestsMatching("*Fixture*")
            excludeTestsMatching("*Stub*")
            excludeTestsMatching("*Kt*")
        }
    }
}
