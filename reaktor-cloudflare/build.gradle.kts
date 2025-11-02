import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*

plugins {
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
        }
    }
    droid {}
    darwin {}
    web {
        dependencies {
            api(npm("hono", "4.9.8"))
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.cloudflare")
}
