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
            commonCoroutines()
            commonSerialization()
        }
    }

    web {
        dependencies = {
            kotlinWrappers()
            webCoroutines()
        }
    }

    droid {
        dependencies = {
            activityFragment()
            androidCoroutines()
//            lifecycle()
            extensions()
        }
    }

    darwin {
        dependencies = {
            
        }
    }
    server {
        dependencies = {
            vertx()
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.core")
}


//dependencies {
//    add("kspCommonMainMetadata", project(":flatinvoker-compiler"))
//}
