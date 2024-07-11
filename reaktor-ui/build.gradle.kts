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
        dependencies = {
            commonCoroutines()
            commonSerialization()
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.materialIconsExtended)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            api(compose.components.resources)
        }
    }

    web {
        webpackConfig = {
            configDirectory = file("${projectDir}/webpack.config.d")
        }
        dependencies = {
            kotlinWrappers()
            react()
            webCoroutines()
        }
        packageJson = file("package.json")
    }

    project.android {
        defaults("dev.shibasis.reaktor.core")
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
//dependencies {
//    add("kspCommonMainMetadata", project(":flatinvoker-compiler"))
//}
