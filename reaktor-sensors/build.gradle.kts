import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*

plugins {
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
}

// Android and Apple, each with a real counter behind it. Still no web{}: a browser has no
// pedometer, and an empty target would only pretend otherwise.
kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
        }
    }
    droid {}
    darwin {}
}

android {
    defaults("dev.shibasis.reaktor.sensors")
}
