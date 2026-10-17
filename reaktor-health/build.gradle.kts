import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.health", targets = Targets.Android + Targets.Apple) {
    dependencies {
        common {
            api(projects.reaktorCore)
            commonCoroutines()
        }
        android { implementation(Versions.Android.HealthConnect) }
    }
}
