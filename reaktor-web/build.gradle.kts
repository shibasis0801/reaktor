import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*

plugins {
    id("dev.shibasis.dependeasy.library")
}
kotlin {
    common {
        dependencies = {

        }
    }
    droid {}
    darwin {}
    server {
        dependencies = {

        }
    }
}

android {
    defaults("dev.shibasis.reaktor.web")
}