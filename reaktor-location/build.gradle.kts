import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.dependencies.useKoin

plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
    
}

kotlin {
    common {
        dependencies = {
            api(project(":reaktor-ui"))
        }
    }
    droid {
        dependencies = {
            api("com.google.android.gms:play-services-location:21.2.0")
        }
    }
    darwin {
        podDependencies = {
            framework {
                linkerOpts("-framework", "CoreLocation")
            }
        }
    }
    web {}
    server {}
    useKoin()
}

android {
    defaults("dev.shibasis.reaktor.location")
}
