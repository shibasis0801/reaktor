import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*


plugins {
    kotlin("multiplatform")
    id("com.android.library")
    kotlin("native.cocoapods")
    id("app.cash.sqldelight")
    id("maven-publish")
    id("dev.shibasis.dependeasy.library")
    kotlin("plugin.serialization")
}

group = "dev.shibasis.flatinvoker.react"
version = "1.0-SNAPSHOT"

kotlin {
    val (commonMain, commonTest) = common {
        dependencies = {
            commonSerialization(Version.Serialization)
            commonCoroutines()
            implementation("app.cash.sqldelight:runtime:${Version.SQLDelight}")
//            implementation("co.touchlab:kermit-crashlytics:${Version.Kermit}")
            implementation("io.ktor:ktor-client-core:${Version.Ktor}")
//            implementation("co.touchlab:kermit:${Version.Kermit}")
        }
    }

    droid {
        dependencies = {
            implementation("app.cash.sqldelight:android-driver:${Version.SQLDelight}")
            api("com.facebook.react:react-native:0.68.5") {
                exclude(module = "fbjni-java-only")
            }
            implementation("com.facebook.fbjni:fbjni:0.2.2")
            implementation("io.ktor:ktor-client-okhttp:${Version.Ktor}")
            androidCoroutines()
        }
    }


//    val xcFramework = XCFramework()
    darwin {
        devBuild = false

        dependencies = {
            implementation("app.cash.sqldelight:native-driver:${Version.SQLDelight}")
            implementation("io.ktor:ktor-client-darwin:${Version.Ktor}")
        }

        podDependencies = {

        }

        targets = {
        }

        cinterops = {}
    }
}


android {
    defaults("dev.shibasis.flatinvoker.react", file("cpp/CMakeLists.txt"))
}

tasks.register<Exec>("customTaskForSomething") {
    group = "batcave"
}









