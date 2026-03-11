import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*

plugins {
    id("dev.shibasis.dependeasy.library")
}

val otelKotlinVersion = "0.1.0"
val firebaseKotlinVersion = "2.4.0"

kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
            api(project(":reaktor-graph"))
            api("io.opentelemetry.kotlin:api:$otelKotlinVersion")
            api("io.opentelemetry.kotlin:noop:$otelKotlinVersion")
            implementation("io.opentelemetry.kotlin:implementation:$otelKotlinVersion")
            api("dev.gitlive:firebase-analytics:$firebaseKotlinVersion")
        }
    }
    droid {}
    darwin {}
    web {}
    server {}
}

android {
    defaults("dev.shibasis.reaktor.telemetry")
}
