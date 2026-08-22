import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*

plugins {
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
}

// Both targets are real here, unlike reaktor-sensors: a health store is the only way to see a
// watch's data, and every platform that has watches has one.
kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
            commonCoroutines()
        }
    }
    droid {
        dependencies {
            implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
        }
    }
    darwin {}
}

android {
    defaults("dev.shibasis.reaktor.health")
}
