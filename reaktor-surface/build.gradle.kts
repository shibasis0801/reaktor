import dev.shibasis.dependeasy.common.commonCoroutines

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.surface") {
        common { dependencies { commonCoroutines() } }
        android {}
        apple {}
        web {}
        jvm {}
    }
}
