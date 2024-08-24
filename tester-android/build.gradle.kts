import dev.shibasis.dependeasy.android.defaults

plugins {
    id("dev.shibasis.dependeasy.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    defaults("dev.shibasis.tester.react.android")
}

dependencies {
    api(project(":reaktor-ui"))

    val hermesPath= "../tester-react/node_modules/hermes-engine/android/"
    debugImplementation(files(hermesPath + "hermes-debug.aar"))
    releaseImplementation(files(hermesPath + "hermes-release.aar"))


    api("androidx.javascriptengine:javascriptengine:1.0.0-beta01")
}