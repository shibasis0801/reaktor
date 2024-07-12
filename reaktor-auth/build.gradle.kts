import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    id("dev.shibasis.dependeasy.library")
    id("app.cash.sqldelight")
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
            api("org.jetbrains.exposed:exposed-core:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-dao:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-jdbc:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-json:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-kotlin-datetime:${Version.Exposed}")
            api("com.impossibl.pgjdbc-ng:pgjdbc-ng:0.8.9")
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.auth")
}
