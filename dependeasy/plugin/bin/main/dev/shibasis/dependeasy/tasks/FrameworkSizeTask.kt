package dev.shibasis.dependeasy.tasks

import org.gradle.api.Task
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.round

/**
 * This task logs the size of the framework.
 *
 * It is currently set to log the size of the aar (without ABI split) and the xcframework (only iosArm64).
 *
 * TODO:
 * - Add support for multiple architectures.
 * - Add support for measuring the c++ binary size.
 * - Generate a report for each ABI.
 * - The plugin should generate binaries per ABI.
 * - Improve the discovery logic.
 *
 * The task depends on the tasks "assembleRelease" and "podPublishReleaseXCFramework".
 *
 * After the task is executed, it calculates the sizes of the aar and the xcframework, and logs them to a CSV file and the console.
 */
fun Task.buildReleaseBinariesLogSizes() {
    group = "reaktor"
    dependsOn("assembleRelease", "podPublishReleaseXCFramework")
    doLast {
        val androidName = project.name
        val name = project.name.replace('-', '_')
        val aarPath = "${project.buildDir}/outputs/aar/$androidName-release.aar"
        val xcfPathBase = "${project.buildDir}/cocoapods/publish/release/$name.xcframework"

        val aarSize = calculateAarSize(aarPath)
        val xcfSize = calculateXcfSize(name, xcfPathBase)

        // Print the sizes to a CSV file and console
        logSizes(aarSize, xcfSize)
    }
}

fun calculateAarSize(aarPath: String): Double {
    return sizeInKb(aarPath)
}

fun calculateXcfSize(name: String, xcfPathBase: String): Double {
    var xcfPath = "$xcfPathBase/ios-arm64_arm64e/$name.framework/$name"
    if (!Files.exists(Paths.get(xcfPath))) {
        xcfPath = "$xcfPathBase/ios-arm64/$name.framework/$name"
    }
    return sizeInKb(xcfPath)
}

fun sizeInKb(filePath: String): Double {
    return round(Files.size(Paths.get(filePath)) / 1024.0)
}

private fun Task.logSizes(aarSize: Double, xcfSize: Double) {
    val csvFile = project.file("./buildSize.csv")
    val date = SimpleDateFormat("dd/MM/yy").format(Date())

    val outputStream = ByteArrayOutputStream()
    project.exec {
        commandLine("git", "rev-parse", "HEAD")
        standardOutput = outputStream
    }
    val commitId = outputStream.toString().trim()

    val dataLine = "$date, $commitId, $aarSize, $xcfSize\n"
    csvFile.appendText(dataLine)
    println("Built Android AAR and Apple XCFramework")
    println("date, commitId, aarSizeKb, xcfSizeKb")
    println(dataLine)
}
