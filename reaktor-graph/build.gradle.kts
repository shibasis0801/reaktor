import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.Compose
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.arrow
import dev.shibasis.dependeasy.common.commonKoin
import dev.shibasis.dependeasy.server.springWebFlux

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.navigation", targets = Targets.All, ui = Compose()) {
    val generateBindings by bindings()
    dependencies {
        common {
            api(Reaktor.GraphRuntime, Reaktor.Ui)
            arrow()
            commonKoin()
        }
        web { api(Versions.Compose.NavigationEvent) }
        jvm { springWebFlux() }
    }
}
