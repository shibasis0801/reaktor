import dev.shibasis.dependeasy.Version
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
            api(project(":compose-flow"))
            api(project(":reaktor-ui"))
            api(project(":reaktor-graph-runtime"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.materialIconsExtended)
        }
    }

    web {}

    droid {}

    darwin {}

    server {
        dependencies {
            api(compose.desktop.currentOs)
            implementation("org.eclipse.elk:org.eclipse.elk.alg.layered:${Version.Elk}")
            implementation("org.eclipse.xtext:org.eclipse.xtext.xbase.lib:${Version.XbaseLib}")
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.blueprint")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    include("**/*Test.class")
    exclude("**/*\$*")
}
