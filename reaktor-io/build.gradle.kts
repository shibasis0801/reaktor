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
    val (commonMain, commonTest) = common {
        dependencies = {
            api(project(":reaktor-core"))
            api("org.jetbrains.kotlinx:kotlinx-io-core:0.3.1")
            commonNetworking()
            commonLogging()
        }
    }

    web {
        dependencies = {

        }
    }

    droid {
        dependencies = {
            firebase(project)
            androidNetworking()
        }
    }

    darwin {
        dependencies = {
            darwinNetworking()
        }
    }
    server {
        dependencies = {
            serverNetworking()
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.io")
}


//dependencies {
//    add("kspCommonMainMetadata", project(":flatinvoker-compiler"))
//}
