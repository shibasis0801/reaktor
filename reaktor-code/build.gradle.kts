import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.analysis.kotlinAnalysis
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.code", targets = Targets.Web + Targets.Android + Targets.Apple + Targets.Jvm) {
    dependencies {
        common { commonCoroutines() }
        jvm {
            api(projects.reaktorGraphRuntime)
            implementation(Versions.Kotlin.CompilerEmbeddable)
            implementation(Versions.Kotlin.SerializationJson)
        }
    }
    val verifyCodeBoundary by boundary(
        description = "Keeps the editing contract usable by a renderer and a language server alike.",
        groupPrefixes = listOf("androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko", "org.eclipse.lsp4j"),
        modules = listOf("reaktor-ui", "reaktor-tooling", "reaktor-graph", "engine"),
        reason = "Code contract picked up a renderer or a server")
}

kotlinAnalysis()
