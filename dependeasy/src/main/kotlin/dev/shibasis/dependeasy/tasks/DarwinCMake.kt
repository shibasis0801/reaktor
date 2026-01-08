package dev.shibasis.dependeasy.tasks

import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.create

private fun Project.isMacOs(): Boolean =
    System.getProperty("os.name")?.lowercase()?.contains("mac") == true

fun Project.darwinCmake(sdk: String): Exec? {
    if (!isMacOs()) return null
    if (!file("cpp").exists()) return null
    val prefix = sdk
    return tasks.create<Exec>("${prefix}CMake") {
        group = "reaktor"
        workingDir = file("cpp")
        commandLine = listOf("bash", "-c", """
            mkdir -p build &&
            cd build &&   
            rm -rf $prefix &&
            cmake -B $prefix -G Xcode \
                -DCMAKE_BUILD_TYPE=Release \
                -Dsdk=${sdk} .. \
                -DiOS=true &&
            cmake --build $prefix --config Release
        """.trimIndent()
        )
    }
}