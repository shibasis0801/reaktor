pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral() } }
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
rootProject.name = "dependeasy"
include(":model", ":toolchain", ":gradle")
