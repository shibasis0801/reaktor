import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*

plugins {
    id("dev.shibasis.dependeasy.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-surface"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
            api("org.jetbrains.compose.ui:ui-backhandler:${project.property("compose.version")}")
        }
    }
    droid {}
    darwin {}
    web {}
    server {
        dependencies {
            api(compose.desktop.currentOs)
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.surface.compose")
}
