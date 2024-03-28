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

include("reaktor-core", "../../reaktor-core")
include("flatbuffers-kotlin", "../../.github_modules/flatbuffers/kotlin/flatbuffers-kotlin")
include("flatinvoker-core", "../../flatinvoker-core")
include("reaktor-io", "../../reaktor-io")
include("flatinvoker-ffi", "../../flatinvoker-ffi" )
include("flatinvoker-react", "../../flatinvoker-react")
include("flatinvoker-compiler", "../../flatinvoker-compiler")
include(":app")