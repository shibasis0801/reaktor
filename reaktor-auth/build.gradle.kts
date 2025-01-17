import com.codingfeline.buildkonfig.compiler.FieldSpec
import com.codingfeline.buildkonfig.gradle.BuildKonfigTask
import dev.shibasis.dependeasy.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*
import java.net.InetSocketAddress
import java.net.Socket


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
            api("io.github.jan-tennert.supabase:gotrue-kt:2.5.4")
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
            api("org.jetbrains.exposed:exposed-core:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-dao:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-jdbc:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-json:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-kotlin-datetime:${Version.Exposed}")
            api("com.auth0:java-jwt:4.4.0")
            api("com.google.api-client:google-api-client:2.7.0")
            api("org.postgresql:postgresql:42.7.3")
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.auth")
}
