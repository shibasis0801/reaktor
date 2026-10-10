import dev.shibasis.dependeasy.common.commonCoroutines

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.code") {
        common { dependencies { commonCoroutines() } }
        web {}
        android {}
        apple {}

        jvm {
            bytecode = 21}
    }


    dependencyBoundary("verifyCodeBoundary") {
        description = "Keeps the editing contract usable by a renderer and a language server alike."
        forbidGroupPrefixes("androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko", "org.eclipse.lsp4j")
        forbidModules("reaktor-ui", "reaktor-tooling", "reaktor-graph", "engine")
        reason.set("Code contract picked up a renderer or a server")
    }
}
