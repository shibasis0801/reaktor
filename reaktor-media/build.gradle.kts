import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
plugins {
    id("dev.shibasis.dependeasy.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-graph"))
            api(project(":reaktor-io"))
            api("io.coil-kt.coil3:coil-compose:3.2.0")
            api("io.coil-kt.coil3:coil-network-ktor3:3.2.0")
        }
    }

    web {
        dependencies {

        }
    }

    droid {
        dependencies {
            camera()
            workManager()
        }
    }

    darwin {
        dependencies {

        }
    }
    server {
        dependencies {

        }
    }
}

android {
    defaults("dev.shibasis.reaktor.media")
}
