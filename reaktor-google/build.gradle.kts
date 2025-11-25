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

        }
    }
}

android {
    defaults("dev.shibasis.reaktor.google")
}
