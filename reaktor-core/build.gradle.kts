import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.web.kotlinWrappers
import dev.shibasis.dependeasy.web.webCoroutines
import dev.shibasis.dependeasy.android.activityFragment
import dev.shibasis.dependeasy.android.androidCoroutines
import dev.shibasis.dependeasy.android.extensions
import dev.shibasis.dependeasy.android.fbjni
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.common.commonLogging
import dev.shibasis.dependeasy.common.commonSerialization
import dev.shibasis.dependeasy.server.serverCoroutines
import dev.shibasis.dependeasy.server.springWebFlux

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    javascript("coreTypeScript").library("buildCoreTypeScript", "js/tsconfig.json", "js/dist", kotlin = true)
    module("dev.shibasis.reaktor.core") {
        common {
            dependencies {
                commonLogging()
                commonCoroutines()
                commonSerialization()
                api(Versions.Kotlin.DateTime)
                api(Versions.Kotlin.AtomicFu)
                api(Versions.Kotlin.ImmutableCollections)
            }
        }

        web {
            dependencies {
                api(npm("reaktor-core", projectDir))
                kotlinWrappers()
                webCoroutines()
            }
        }

        android {
            dependencies {
                activityFragment()
                androidCoroutines()
                fbjni()

                extensions()
            }
        }
        apple {}

        jvm {
            dependencies {
                serverCoroutines()
                springWebFlux()
                api(Versions.Data.Exposed)
                api(Versions.Data.ExposedJdbc)
                api(Versions.Data.Postgis)
            }
        }
    }


    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        exclude(
            "**/ChmAdapter.class",
            "**/ReaktorChmAdapter.class",
            "**/JavaChmAdapter.class",
            "**/SyncHashChmAdapter.class",
        )
    }
}
