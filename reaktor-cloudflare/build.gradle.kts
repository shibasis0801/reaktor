import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.configuration.ReaktorTests
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.cloudflare", targets = Targets.Android + Targets.Apple + Targets.Web) {
    val generateBindings by bindings()
    val buildTypeScript by typescript(directory = "ts", configuration = "tsconfig.json", output = "dist", kotlin = listOf(kotlinJsLibrary(Reaktor.Cloudflare)))
    dependencies {
        common { api(Reaktor.AuthCore, Reaktor.Core, Reaktor.Service, Reaktor.GraphRuntime, Reaktor.Io, Reaktor.Secrets) }
        web {
            api(npm("hono", Versions.Web.Hono))
            api(npm("partyserver", Versions.Web.PartyServer))
            api(npm("postgres", Versions.Web.Postgres))
        }
        webTest {
            implementation(Reaktor.Work)
            implementation(npm("miniflare", Versions.Web.Miniflare))
        }
    }
    val node by tests(Suite.JavaScript, environment = ReaktorTests.Cloudflare)
    val verifyCloudflareRuntimeBoundary by boundary(
        configuration = ConfigurationRef.JavaScriptCompileClasspath,
        moduleSuffix = "-js",
        groupPrefixes = listOf("androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko", "ai.bestbuds"),
        modules = listOf("reaktor-auth", "reaktor-graph", "reaktor-ui", "engine", "kernel"),
        reason = "Cloudflare hosts must remain headless",
    )
}
