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
            api("com.impossibl.pgjdbc-ng:pgjdbc-ng:0.8.9")
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.auth")
}
