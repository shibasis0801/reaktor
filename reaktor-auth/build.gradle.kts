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

kotlin {
    common {
        dependencies = {
            api(project(":reaktor-ui"))
            api(project(":reaktor-navigation"))
            api(project(":reaktor-io"))
        }
    }

    web {
        dependencies = {

        }
    }

    droid {
        dependencies = {
            implementation("androidx.credentials:credentials:1.3.0")
            // Android 13 and below.
            implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
            implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
        }
    }

    darwin {
        podDependencies = {
            pod("GoogleSignIn", "8.0.0")
        }
    }
    server {
        dependencies = {
            api("com.auth0:java-jwt:4.4.0")
            api("com.google.api-client:google-api-client:2.7.0")
            api("org.springframework.boot:spring-boot-starter-data-r2dbc:${Version.SDK.Spring}")
            api("org.postgresql:r2dbc-postgresql:1.0.7.RELEASE")
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.auth")
}

