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
            api(project(":reaktor-ui"))
        }
    }
    droid {}
    darwin {}
    web {}
    server {
        dependencies {
            api("io.github.resilience4j:resilience4j-kotlin:${resilience4jVersion}")
            api("io.github.resilience4j:resilience4j-retry:${resilience4jVersion}")
            api("io.github.resilience4j:resilience4j-circuitbreaker:${resilience4jVersion}")
            api("io.github.resilience4j:resilience4j-ratelimiter:${resilience4jVersion}")
            api("io.github.resilience4j:resilience4j-bulkhead:${resilience4jVersion}")
        }
    }
    useKoin()
}

android {
    defaults("dev.shibasis.reaktor.navigation")
}
