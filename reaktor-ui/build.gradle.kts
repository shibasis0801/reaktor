import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.dependencies.useKoin

plugins {
    id("dev.shibasis.dependeasy.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
            api(project(":reaktor-io"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.materialIconsExtended)
            api("io.coil-kt.coil3:coil-compose:3.2.0")
            api("io.coil-kt.coil3:coil-network-ktor3:3.2.0")
            api("io.coil-kt.coil3:coil-svg:3.2.0")
        }
        testDependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            api(compose.uiTest)
        }
    }

    web {
        webpackConfig {
            configDirectory = file("${projectDir}/webpack.config.d")
        }
        dependencies {
            kotlinWrappers()
            react()
            webCoroutines()
        }
        packageJson = file("package.json")
    }

    droid {
        dependencies {
            activityFragment()
            androidCoroutines()
            fbjni()
//            lifecycle()
            extensions()
        }
    }

    darwin {
        dependencies {
            // todo kotlin native needs this, should be transitive but there is some bug https://github.com/Kotlin/kotlinx.coroutines/pull/3996/files
            api("org.jetbrains.kotlinx:atomicfu:0.23.1")
        }
    }
    server {
        dependencies {

        }
    }
}

android {
    defaults("dev.shibasis.reaktor.ui")
}