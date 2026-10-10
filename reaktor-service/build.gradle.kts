import dev.shibasis.dependeasy.Versions

import dev.shibasis.dependeasy.server.springWebFlux

import org.gradle.api.tasks.testing.Test

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.service") {
        common {
            dependencies {
                // reaktor-io brings reaktor-core (serialization/coroutines/framework/StatusCode) and the
                // Ktor client (`http`) used by the generic Service client. The service layer is graph-neutral.
                api(project(":reaktor-io"))
            }
            testDependencies {
                implementation(Versions.Kotlin.KtorMock)
            }
        }
        android {}
        apple {}
        web {}

        jvm {
            dependencies {
                // Spring WebFlux router (SpringRouter.toRouter/nest) lives in jvmMain.
                springWebFlux()
                api(Versions.Server.ReactorKotlin)
                api(Versions.Kotlin.CoroutinesReactor)
            }
        }
    }


    tasks.withType<Test>().configureEach {
        // Serializable request/response DTOs and handler helpers in commonTest are not tests; disable
        // auto-discovery so Gradle/JUnit don't try to execute them.
        exclude("**/*\$*")
    }

    testClasses()
}
