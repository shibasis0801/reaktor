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

import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.core", targets = Targets.All) {
    val buildCoreTypeScript by typescript(configuration = "js/tsconfig.json", output = "js/dist", kotlinConsumer = true)
    dependencies {
        common {
            commonLogging()
            commonCoroutines()
            commonSerialization()
            api(Versions.Kotlin.DateTime)
            api(Versions.Kotlin.AtomicFu)
            api(Versions.Kotlin.ImmutableCollections)
        }
        web {
            api(npm("reaktor-core", projectDir))
            kotlinWrappers()
            webCoroutines()
        }
        android {
            activityFragment()
            androidCoroutines()
            fbjni()
            extensions()
        }
        jvm {
            serverCoroutines()
            springWebFlux()
            api(Versions.Data.Exposed)
            api(Versions.Data.ExposedJdbc)
            api(Versions.Data.Postgis)
        }
    }
}
