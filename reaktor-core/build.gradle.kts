import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*

plugins {
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies {
            commonLogging()
            commonCoroutines()
            commonSerialization()
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            api("org.jetbrains.kotlinx:atomicfu:0.28.0")
            api("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")
        }
    }

    web {
        dependencies {
            api(npm("reaktor-core", file("js")))
            kotlinWrappers()
            webCoroutines()
        }
    }

    droid {
        dependencies {
            activityFragment()
            androidCoroutines()
            fbjni()
//            lifecycle()
            extensions()
        }
    }

    darwin {
        dependencies {}
    }

    server {
        dependencies {
            springWebFlux()
        }
    }

}

android {
    defaults("dev.shibasis.reaktor.core")
}




val jsProjectDir = file("js")

//val npmInstall by tasks.registering(Exec::class) {
//    group = "npm"
//    workingDir = jsProjectDir
//    commandLine("npm", "install")
//
//    inputs.file(jsProjectDir.resolve("package.json"))
//    outputs.dir(jsProjectDir.resolve("node_modules"))
//}
//
//
//val compileTypeScript by tasks.registering(Exec::class) {
//    group = "npm"
//    workingDir = jsProjectDir
//    commandLine("npm", "run", "build")
//    inputs.dir(jsProjectDir.resolve("src"))
//    dependsOn(npmInstall)
//}
//
//tasks.named("jsProcessResources") {
//    dependsOn(compileTypeScript)
//}