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
