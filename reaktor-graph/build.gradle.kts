import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.dependencies.useKoin

plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
    
}
val resilience4jVersion = "2.2.0"

kotlin {
    common {
        dependencies {
            api(project(":reaktor-graph-port"))
            api(project(":reaktor-ui"))
            api(project(":reaktor-db"))
            arrow()
        }
    }
    droid {}
    darwin {}
    web {}
    server {
        dependencies {
            springWebFlux()
            api("io.projectreactor.kotlin:reactor-kotlin-extensions:1.2.3")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:${Version.Coroutines}")
            api("org.jetbrains.exposed:exposed-core:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-jdbc:${Version.Exposed}")
            api("org.postgresql:postgresql:42.7.3")
        }
    }
    useKoin()
}

android {
    defaults("dev.shibasis.reaktor.navigation")
}
