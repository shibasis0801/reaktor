import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.common.commonSerialization

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.secrets") {
        common {
            dependencies {
                api(project(":reaktor-auth-core"))
                api(project(":reaktor-core"))
                commonCoroutines()
                commonSerialization(protobuf = false)
            }
        }
        android {}
        apple {}
        web {}

        jvm {
            dependencies {
                api(Versions.Google.Auth)
            }
        }
    }
}
