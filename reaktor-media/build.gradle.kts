import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.android.camera
import dev.shibasis.dependeasy.android.workManager

plugins { id("dev.shibasis.dependeasy.compose-library") }

dependeasy {
    module("dev.shibasis.reaktor.media") {
        common {
            dependencies {
                api(project(":reaktor-service"))
                api(project(":reaktor-ui"))
                api(project(":reaktor-io"))
                api(Versions.Compose.Coil)
                api(Versions.Compose.CoilKtor)
            }
        }
        web {}

        android {
            dependencies {
                camera()
                workManager()
            }
        }
        apple {}
        jvm {}
    }
}
