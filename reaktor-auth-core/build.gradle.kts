import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.common.commonSerialization
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.auth.core", targets = Targets.Web + Targets.Android + Targets.Apple + Targets.Jvm, bytecode = 21) {
    dependencies { common { commonCoroutines(); commonSerialization(protobuf = false) } }
    val verifyAuthCoreBoundary by boundary(
        groupPrefixes = listOf("androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko", "org.springframework"),
        modules = listOf("reaktor-graph", "reaktor-graph-runtime", "reaktor-db", "reaktor-ui", "kernel", "engine"),
        reason = "Auth core must remain independent of UI, graph, database and server hosting")
}
