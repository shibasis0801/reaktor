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
            commonLogging()
            commonCoroutines()
            commonSerialization()
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2") {
                exclude("org.jetbrains.kotlinx", "serialization-core")
                exclude("org.jetbrains.kotlinx", "serialization-json")
                exclude("org.jetbrains.kotlinx", "serialization-protobuf")
            }
            api("org.jetbrains.kotlinx:atomicfu:0.28.0")
        }
    }

    web {
        dependencies = {
            kotlinWrappers()
            webCoroutines()
        }
    }

    droid {
        dependencies = {
            activityFragment()
            androidCoroutines()
            fbjni()
//            lifecycle()
            extensions()
        }
    }

    darwin {
        dependencies = {}
    }
    server {
        dependencies = {
            springWebFlux()
        }
    }

}

android {
    defaults("dev.shibasis.reaktor.core")
}
