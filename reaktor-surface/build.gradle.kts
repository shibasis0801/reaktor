import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.web.kotlinWrappers

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.surface", targets = Targets.All) {
    val packageSources by webPackage("ts")
    dependencies {
        common { commonCoroutines() }
        web {
            kotlinWrappers()
            api(Versions.Kotlin.wrapper("react"))
            api(Versions.Kotlin.wrapper("react-dom"))
        }
    }
}
