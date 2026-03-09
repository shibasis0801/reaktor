import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.web.*

plugins {
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-cloudflare"))
        }
    }
    droid {}
    darwin {}
    web {
        moduleName = "reaktor-hello-worker-kotlin"
    }
}

android {
    defaults("dev.shibasis.reaktor.experiments.cloudflarehello")
}
