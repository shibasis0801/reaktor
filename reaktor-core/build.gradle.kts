import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*

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
            fbjni()
//            lifecycle()
            extensions()
        }
    }

    darwin {
        dependencies = {
            // todo kotlin native needs this, should be transitive but there is some bug https://github.com/Kotlin/kotlinx.coroutines/pull/3996/files
            api("org.jetbrains.kotlinx:atomicfu:0.23.1")
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
