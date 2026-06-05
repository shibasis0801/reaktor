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
            api(project(":reaktor-graph"))
            api(project(":reaktor-io"))
        }
    }
    droid {}
    darwin {}
    web {
        dependencies {
            api(npm("hono", "4.12.23"))
            api(npm("partyserver", "0.5.6"))
            api(npm("postgres", "3.4.9"))
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.cloudflare")
}
