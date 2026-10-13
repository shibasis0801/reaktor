import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.compose-library") }

dependeasy {
    module("dev.shibasis.reaktor.blueprint") {
        common {
            dependencies {
                api(projects.composeFlow)
                api(projects.reaktorUi)
                api(projects.reaktorGraphRuntime)
                api(Versions.Compose.Runtime)
                api(Versions.Compose.Foundation)
                api(Versions.Compose.Material3)
            }
        }
        web {}
        android {}
        apple {}

        jvm {
            dependencies {
                api(Versions.Compose.Desktop)
                implementation(Versions.Compose.Elk)
                implementation(Versions.Compose.Xbase)
            }
        }
    }


    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        include("**/*Test.class")
        exclude("**/*\$*")
    }
}
