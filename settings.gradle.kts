import java.nio.file.*

rootProject.name = "flatInvoker"

pluginManagement {
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

// Extension function to execute shell commands
fun String.execute() {
    val process = Runtime.getRuntime().exec(this)
    process.inputStream.reader(Charsets.UTF_8).use { reader ->
        println(reader.readText())
    }
}


// clone a git repo if it does not exist
fun gitDependency(path: String, repoURL: String) {
    val localPath = Paths.get(file(path).absolutePath)
    if (!Files.exists(localPath)) {
        val cloneCommand = "git clone $repoURL ${localPath.toAbsolutePath()}"
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

The reason was flatc, the binary was not accessible from the path.
If we setup that, these hacks should not be needed.
*/


gitDependency("flatbuffers", "https://github.com/google/flatbuffers.git")
gitDependency("googletest", "https://github.com/google/googletest.git")
gitDependency("skiko", "https://github.com/JetBrains/skiko.git")

include("flatbuffers-kotlin", "flatbuffers/kotlin/flatbuffers-kotlin")
includeBuild("test-kmm")
include(":lib-kotlin")

