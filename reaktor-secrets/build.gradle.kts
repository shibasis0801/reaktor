import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.common.commonSerialization
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.secrets", targets = Targets.All) {
    dependencies {
        common {
            api(projects.reaktorAuthCore)
            api(projects.reaktorCore)
            commonCoroutines()
            commonSerialization(protobuf = false)
        }
        jvm { api(Versions.Google.Auth) }
    }
}
