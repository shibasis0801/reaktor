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
            api(project(":reaktor-auth"))
            api(project(":reaktor-core"))
            api(project(":reaktor-service"))
            api(project(":reaktor-io"))
            api(project(":reaktor-secrets"))
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
