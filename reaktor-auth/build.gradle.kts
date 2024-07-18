import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*

plugins {
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies = {
            api(project(":reaktor-core"))
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
        dependencies = {

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
            implementation("org.postgresql:postgresql:42.7.3")
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.auth")
}
