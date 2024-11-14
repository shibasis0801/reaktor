import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*

plugins {
    id("dev.shibasis.dependeasy.library")
    id("dev.shibasis.publisheasy")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    common {
        dependencies = {
            api(project(":reaktor-ui"))
            api(project(":reaktor-navigation"))
            api(project(":reaktor-io"))
            api("io.github.jan-tennert.supabase:gotrue-kt:2.5.4")
        }
    }

    web {
        dependencies = {

        }
    }

    droid {
        dependencies = {

        }
    }

    darwin {
        podDependencies = {
            pod("GoogleSignIn", "8.0.0")
        }
    }
    server {
        dependencies = {
            // sqldelight does not support jvm postgres in a multiplatform module yet.
            api("org.jetbrains.exposed:exposed-core:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-dao:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-jdbc:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-json:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-kotlin-datetime:${Version.Exposed}")
            api("com.auth0:java-jwt:4.4.0")
            implementation("org.postgresql:postgresql:42.7.3")
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.auth")
}