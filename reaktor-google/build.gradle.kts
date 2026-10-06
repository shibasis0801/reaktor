import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.dependencies.useKoin

plugins {
//    id("org.jetbrains.compose")
//    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-auth"))
            api(project(":reaktor-work"))
        }
    }
    droid {
        dependencies {

        }
    }
    darwin {
        podDependencies {

        }
    }
    web {}
    server {
        dependencies {
            api("com.google.apis:google-api-services-sheets:v4-rev20250211-2.0.0")
            api("com.google.cloud:google-cloud-pubsub:1.147.0")
            implementation("com.google.apis:google-api-services-calendar:v3-rev20260708-2.0.0")
            implementation("com.google.apis:google-api-services-drive:v3-rev20260916-2.0.0")
            implementation("com.google.apis:google-api-services-youtube:v3-rev20260924-2.0.0")
            api("com.google.auth:google-auth-library-oauth2-http:1.42.1")
            implementation(project(":reaktor-crypto"))
        }
    }

    sourceSets.jvmTest.dependencies {
        implementation(kotlin("test"))
        implementation("com.squareup.okhttp3:mockwebserver:5.4.0")
        implementation("org.postgresql:postgresql:42.7.13")
    }
}

android {
    defaults("dev.shibasis.reaktor.google")
}
