import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import kotlin.math.round

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinCocoapods)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()



    cocoapods {
        summary = "Some description for the Shared Module"
        homepage = "Link to the Shared Module homepage"
        version = "1.0"
        ios.deploymentTarget = "16.0"
        podfile = project.file("../app-ios/Podfile")
        framework {
            baseName = "lib_core"
            isStatic = true
        }
    }
    
    sourceSets {
        commonMain.dependencies {
            //put your multiplatform dependencies here
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("androidx.test:core-ktx:1.5.0")
                implementation("junit:junit:4.13.2")
                implementation("androidx.test.ext:junit:1.1.3")
                implementation("androidx.test.ext:junit-ktx:1.1.3")
                implementation("androidx.test.espresso:espresso-core:3.4.0")
            }
        }
    }
}

android {
    namespace = "dev.shibasis.reaktor"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }

    signingConfigs {
        create("release") {
            storeFile = file("../../../keys/devsign")
            storePassword = "password"
            keyAlias = "devsign"
            keyPassword = "password"
        }
        // The actual release config (need to setup env for passwords)
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // proguard
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

// Create a new gradle task
task("buildReleaseBinaries") {
    group = "reaktor"
    dependsOn("assembleRelease", "podPublishReleaseXCFramework")
    doLast {
        // Define the paths to the AAR and XCFramework files
        val aarFilePath = "${project.buildDir}/outputs/aar/lib-core-release.aar"
        val xcFrameworkFilePath = "${project.buildDir}/cocoapods/publish/release/lib_core.xcframework/ios-arm64/lib_core.framework/lib_core"

        val sizeInKb = { filePath: String ->
            round(Files.size(Paths.get(filePath)) / 1024.0)
        }

        // Get the sizes of the files
        val aarSize = sizeInKb(aarFilePath)
        val xcfSize = sizeInKb(xcFrameworkFilePath)

        // Print the sizes to a CSV file
        val csvFile = file("./buildSize.csv")
        val date = SimpleDateFormat("dd/MM/yy").format(Date())

        val outputStream = ByteArrayOutputStream()
        exec {
            commandLine("git", "rev-parse", "HEAD")
            standardOutput = outputStream
        }
        val commitId = outputStream.toString().trim()

        val dataLine ="$date, $commitId, $aarSize, $xcfSize\n"
        csvFile.appendText(dataLine)
        println("Built Android AAR and Apple XCFramework")
        println(dataLine)
    }
}

