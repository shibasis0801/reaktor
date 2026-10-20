import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.graph.port", targets = Targets.All) {
    dependencies { common { api(Versions.Kotlin.AtomicFu); api(Reaktor.Core) } }
    val checkGraphPortTypeScript by typescript(kotlin = listOf(kotlinJsLibrary(Reaktor.GraphPort)))
}
