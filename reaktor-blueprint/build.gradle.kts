import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.Compose
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.blueprint", targets = Targets.All, ui = Compose()) {
    dependencies {
        common {
            api(Reaktor.ComposeFlow, Reaktor.Ui, Reaktor.GraphRuntime)
            api(Versions.Compose.Runtime)
            api(Versions.Compose.Foundation)
            api(Versions.Compose.Material3)
        }
        jvm {
            api(Versions.Compose.Desktop)
            implementation(Versions.Compose.Elk)
            implementation(Versions.Compose.Xbase)
        }
    }
}
