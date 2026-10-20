import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonKoin
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.compose-library") }

library(namespace = "dev.shibasis.reaktor.location", targets = Targets.All) {
    dependencies {
        common {
            api(projects.reaktorUi)
            commonKoin()
        }
        android { api(Versions.Android.Location) }
    }
}
