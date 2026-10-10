import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonCoroutines

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    // Both targets are real here, unlike reaktor-sensors: a health store is the only way to see a
    // watch's data, and every platform that has watches has one.
    module("dev.shibasis.reaktor.health") {
        common {
            dependencies {
                api(project(":reaktor-core"))
                commonCoroutines()
            }
        }

        android {
            dependencies {
                implementation(Versions.Android.HealthConnect)
            }
        }
        apple {}
    }
}
