import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.Suite
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.board", targets = Targets.Android + Targets.Jvm + Targets.Web) {
    dependencies {
        common {
            api(Reaktor.Service)
            api(Versions.Kotlin.SerializationJson)
        }
        web { api(Reaktor.Surface) }
    }
    web(module = "reaktor-board-kt")
    val unit by tests(Suite.Jvm)
    val node by tests(Suite.JavaScript)
}
