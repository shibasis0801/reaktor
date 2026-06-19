import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*

plugins {
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-auth"))
            api(project(":reaktor-core"))
            commonCoroutines()
            commonSerialization(protobuf = false)
        }
    }

    droid {}
    darwin {}
    web {}
    server {
        dependencies {
            api("com.google.auth:google-auth-library-oauth2-http:1.42.1")
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.secrets")
}
