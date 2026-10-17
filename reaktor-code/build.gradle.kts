import dev.shibasis.dependeasy.analysis.kotlinAnalysis
import dev.shibasis.dependeasy.common.commonCoroutines

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.code") {
        common { dependencies { commonCoroutines() } }
        web {}
        android {}
        apple {}

        jvm {
            dependencies {
                api(projects.reaktorGraphRuntime)
                implementation(dev.shibasis.dependeasy.Versions.Kotlin.CompilerEmbeddable)
                implementation(dev.shibasis.dependeasy.Versions.Kotlin.SerializationJson)
            }
        }
    }


    dependencyBoundary("verifyCodeBoundary") {
        description = "Keeps the editing contract usable by a renderer and a language server alike."
        forbidGroupPrefixes("androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko", "org.eclipse.lsp4j")
        forbidModules("reaktor-ui", "reaktor-tooling", "reaktor-graph", "engine")
        reason.set("Code contract picked up a renderer or a server")
    }
}

kotlinAnalysis()
