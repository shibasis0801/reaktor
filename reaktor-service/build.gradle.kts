import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.server.springWebFlux

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.service", targets = Targets.All) {
    dependencies {
        common { api(Reaktor.Io) }
        commonTest { implementation(Versions.Kotlin.KtorMock) }
        jvm {
            springWebFlux()
            api(Versions.Server.ReactorKotlin)
            api(Versions.Kotlin.CoroutinesReactor)
        }
    }
}
