import dev.shibasis.dependeasy.*
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

extra["kotlinx-serialization.version"] = "1.8.0"


kotlin {
    common {
        dependencies {
            api(project(":reaktor-ui"))
            api(project(":reaktor-navigation"))
            api(project(":reaktor-io"))
        }
    }

    web {
        dependencies {

        }
    }

    droid {
        dependencies {
            implementation("androidx.credentials:credentials:1.3.0")
            // Android 13 and below.
            implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
            implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
        }
    }

    darwin {
        podDependencies {
            pod("GoogleSignIn", "8.0.0")
        }
    }

    server {
        dependencies {
            api("org.springframework.boot:spring-boot-starter-webflux:${Version.SDK.SpringBoot}")
            api("org.springframework.boot:spring-boot-starter-oauth2-resource-server:${Version.SDK.SpringBoot}")
            api("org.springframework.boot:spring-boot-starter-security:${Version.SDK.SpringBoot}")
            api("org.jetbrains.exposed:exposed-core:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-jdbc:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-json:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-kotlin-datetime:${Version.Exposed}")
            api("org.postgresql:postgresql:42.7.3")
            api("io.projectreactor.kotlin:reactor-kotlin-extensions:1.2.3")
            api("org.jetbrains.kotlin:kotlin-reflect:${Version.SDK.Kotlin}")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:${Version.Coroutines}")
            api("io.github.sebasbaumh:postgis-java-ng:23.2.0")
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.auth")
}

