pluginManagement {
    val source = file("src/main/kotlin/dev/shibasis/dependeasy/toolchain/ToolchainVersions.kt").readText()
    val kotlin = Regex("const val Kotlin = \"([^\"]+)\"").find(source)!!.groupValues[1]
    repositories { gradlePluginPortal(); google(); mavenCentral() }
    plugins {
        id("org.jetbrains.kotlin.jvm") version kotlin
        id("org.jetbrains.kotlin.plugin.sam.with.receiver") version kotlin
    }
}
rootProject.name = "dependeasy"
