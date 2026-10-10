import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.compose-library") }

dependeasy {
    module("dev.shibasis.reaktor.blueprint") {
        common {
            dependencies {
                api(project(":compose-flow"))
                api(project(":reaktor-ui"))
                api(project(":reaktor-graph-runtime"))
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
