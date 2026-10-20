import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets
plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.tactile", targets = Targets.All) {
    val generateBindings by bindings(kotlinConsumer = false)
    dependencies { common { api(projects.reaktorCore) } }
}
