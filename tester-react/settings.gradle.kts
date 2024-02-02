import dev.shibasis.dependeasy.utils.includeWithPath

rootProject.name = "rn-batcave"

pluginManagement {
    includeBuild("dependeasy")
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("dev.shibasis.dependeasy.settings")
}


apply(from = file("./node_modules/@react-native-community/cli-platform-android/native_modules.gradle"));
val applyNativeModulesSettingsGradle = extra["applyNativeModulesSettingsGradle"] as groovy.lang.Closure<*>
applyNativeModulesSettingsGradle(settings)


includeWithPath("android", "android/app")
