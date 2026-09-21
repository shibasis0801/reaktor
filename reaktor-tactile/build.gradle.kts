import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.common.*

plugins {
    id("dev.shibasis.dependeasy.library")
}

// Haptics: a vibrator on Android, the Taptic Engine on iOS. Nothing else.
//
// The build file started as a copy of :reaktor-ui's and carried Compose, Coil and the React
// wrappers with it — none of which this module has ever imported. They are gone; adding a buzz
// to an app should not pull an image loader in behind it.
kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
        }
    }
    droid {
    }
    darwin {
    }
}

android {
    defaults("dev.shibasis.reaktor.tactile")
}
