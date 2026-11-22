import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

// reaktor-auth's wire contract: the AuthService routes and the types they carry, with no logic and
// no UI. reaktor-auth depends on it, so apps see the same packages; a headless host such as a
// Cloudflare Worker can serve the contract without taking reaktor-auth's Compose UI with it.
library(namespace = "dev.shibasis.reaktor.auth.contract", targets = Targets.All) {
    dependencies { common { api(Reaktor.AuthCore, Reaktor.Core, Reaktor.Service) } }
    val verifyAuthContractBoundary by boundary(
        groupPrefixes = listOf("androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko"),
        modules = listOf("reaktor-auth", "reaktor-ui", "reaktor-graph", "reaktor-graph-runtime", "reaktor-db", "kernel", "engine"),
        reason = "The auth contract must stay headless so Workers and servers can serve it")
}
