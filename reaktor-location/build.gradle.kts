import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.dependencies.useKoin

plugins { id("dev.shibasis.dependeasy.compose-library") }

dependeasy {
    module("dev.shibasis.reaktor.location") {
        common {
            dependencies {
                api(project(":reaktor-ui"))
            }
        }

        android {
            dependencies {
                api(Versions.Android.Location)
            }
        }
        apple {}
        web {}
        jvm {}

        kotlin {
            useKoin()
        }
    }
}
