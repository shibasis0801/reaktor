import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.common.commonSerialization

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.auth.core") {
        common { dependencies { commonCoroutines(); commonSerialization(protobuf = false) } }
        web {}
        android {}
        apple {}

        jvm {
            bytecode = 21}
    }


    dependencyBoundary("verifyAuthCoreBoundary") {
        forbidGroupPrefixes("androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko", "org.springframework")
        forbidModules("reaktor-graph", "reaktor-graph-runtime", "reaktor-db", "reaktor-ui", "kernel", "engine")
        reason.set("Auth core must remain independent of UI, graph, database and server hosting")
    }
}
