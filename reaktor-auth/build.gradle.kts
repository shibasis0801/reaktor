import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.*

import org.gradle.api.tasks.Exec

plugins { id("dev.shibasis.dependeasy.compose-library") }

dependeasy {
    module("dev.shibasis.reaktor.auth") {
        common {
            dependencies {
                api(project(":reaktor-auth-core"))
                api(project(":reaktor-ui"))
                api(project(":reaktor-graph"))
                api(project(":reaktor-service"))
                api(project(":reaktor-db"))
                api(project(":reaktor-io"))
                api(Versions.Data.Jwt)
            }
        }
        web {}

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
                api(project(":reaktor-security"))
                api(project(":reaktor-tooling"))
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
