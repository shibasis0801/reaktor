package dev.shibasis.dependeasy.tasks

import org.gradle.api.Task
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.round

/*
Right now it checks the aar size(no arch split) and the xcframework size assuming a single target (iosArm64) is exposed
todo Multi arch support is pending
 */
fun Task.logFrameworkSize() {
    group = "reaktor"
    dependsOn("assembleRelease", "podPublishReleaseXCFramework")
    doLast {
        /*
        Todo
        Report for each ABI
        Plugin should generate binaries per ABI
        Right now discovery logic is shady
         */
        val name = project.name
        val aarPath = "${project.buildDir}/outputs/aar/$name-release.aar"


        var xcfPath = "${project.buildDir}/cocoapods/publish/release/$name.xcframework/ios-arm64_arm64e/$name.framework/$name"
        // check if file exists at xcframework path
        if (!Files.exists(Paths.get(xcfPath))) {
            xcfPath = "${project.buildDir}/cocoapods/publish/release/$name.xcframework/ios-arm64/$name.framework/$name"
        }

        val sizeInKb = { filePath: String ->
            round(Files.size(Paths.get(filePath)) / 1024.0)
        }

        // Get the sizes of the files
        val aarSize = sizeInKb(aarPath)
        val xcfSize = sizeInKb(xcfPath)

        // Print the sizes to a CSV file
        val csvFile = project.file("./buildSize.csv")
        val date = SimpleDateFormat("dd/MM/yy").format(Date())

        val outputStream = ByteArrayOutputStream()
        project.exec {
            commandLine("git", "rev-parse", "HEAD")
            standardOutput = outputStream
        }
        val commitId = outputStream.toString().trim()

        val dataLine ="$date, $commitId, $aarSize, $xcfSize\n"
        csvFile.appendText(dataLine)
        println("Built Android AAR and Apple XCFramework")
        println("date, commitId, aarSizeKb, xcfSizeKb")
        println(dataLine)
    }
}