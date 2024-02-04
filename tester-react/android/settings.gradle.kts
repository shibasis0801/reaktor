import groovy.lang.Closure

pluginManagement {
    includeBuild("../../dependeasy") {
        name = "dependeasy-copy"
    }
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

fun include(name: String, path: String? = null) {
    val newName = ":$name"
    settings.include(newName)
    if (path != null) {
        project(newName).projectDir = File(path)
    }
}
rootProject.name = "tester-react"

apply(from = file("../node_modules/@react-native-community/cli-platform-android/native_modules.gradle"));
val applyNativeModulesSettingsGradle = extra["applyNativeModulesSettingsGradle"] as Closure<Any>
applyNativeModulesSettingsGradle(settings)

include(":app")
include("flatinvoker-react", "../../flatinvoker-react")