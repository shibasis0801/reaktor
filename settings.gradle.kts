import java.nio.file.*

rootProject.name = "flatInvoker"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("com.google.firebase.crashlytics").version("2.9.5")
        id("com.google.devtools.ksp").version("1.9.21-1.0.16")
        id("org.jetbrains.compose").version("1.5.11")
    }
}

fun include(name: String, path: String? = null) {
    val newName = ":$name"
    settings.include(newName)
    if (path != null) {
        project(newName).projectDir = File(path)
    }
}

// Extension function to execute shell commands
fun String.execute() {
    val process = Runtime.getRuntime().exec(this)
    process.inputStream.reader(Charsets.UTF_8).use { reader ->
        println(reader.readText())
    }
}


val githubDir = ".github_modules"
// clone a git repo if it does not exist
fun gitDependency(repoURL: String) {
    val repoName = repoURL.split(".git").first().split("/").last()
    val path = "$githubDir/$repoName"

    val localPath = Paths.get(file(path).absolutePath)
    if (!Files.exists(localPath)) {
        val cloneCommand = "git clone $repoURL $path"
        println("Executing: $cloneCommand")
        cloneCommand.execute()
    } else {
        println("Repository already exists at ${localPath.toAbsolutePath()}")
    }
}
/*
I had to change few things in the flatbuffers repo
1. Disable tests in cmakeLists
option(FLATBUFFERS_BUILD_TESTS "Enable the build of tests and samples." OFF)

2. Disable GenerateFBTestClasses task in flatbuffers-kotlin build.gradle.kts

3. added this for new gradle versions (revert please)
 sourceSets {
    all {
      languageSettings.optIn("kotlin.experimental.ExperimentalNativeApi")
    }

The reason was flatc, the binary was not accessible from the path.
If we setup that, these hacks should not be needed.
*/


gitDependency("https://github.com/google/flatbuffers.git")
gitDependency("https://github.com/google/googletest.git")
gitDependency("https://github.com/JetBrains-Research/reflekt.git")

includeBuild("dependeasy")

include("flatbuffers-kotlin", ".github_modules/flatbuffers/kotlin/flatbuffers-kotlin")
include(":flatinvoker-core")
include(":flatinvoker-react")
include(":flatinvoker-compiler")
include(":reaktor-core")


includeBuild("tester-react/android")
