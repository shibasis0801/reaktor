import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.compose-library") }

library(namespace = "dev.shibasis.reaktor.surface.compose", targets = Targets.All) {
    dependencies {
        common {
            api(projects.reaktorSurface)
            api(Versions.Compose.Runtime)
            api(Versions.Compose.Foundation)
            api(Versions.Compose.Ui)
            api(Versions.Compose.BackHandler)
        }
        commonTest { implementation(Versions.Compose.UiTest) }
        jvm { api(Versions.Compose.Desktop) }
        jvmTest { implementation(Versions.Kotlin.CoroutinesDebug) }
    }
}
