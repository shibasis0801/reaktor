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
            api("org.jetbrains.kotlinx:atomicfu:0.28.0")
        }
    }
    droid {}
    darwin {}
    web {}
    server {}
}

android {
    defaults("dev.shibasis.reaktor.graph.port")
}
