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
        dependencies {
            api(project(":reaktor-core"))
            commonSerialization()
        }
    }
    web {}
    droid {}
    darwin {}
    server {}
}

android {
    defaults("dev.shibasis.reaktor.performance")
}
