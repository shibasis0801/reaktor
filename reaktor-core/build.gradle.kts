import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*

plugins {
    id("dev.shibasis.dependeasy.library")
//    id("com.google.devtools.ksp")
}

kotlin {
    val (commonMain, commonTest) = common {
        dependencies = {
            commonCoroutines()
            commonSerialization()
        }

        testDependencies = {
            api(kotlin("test"))
        }
    }

    web {
        dependencies = {
            kotlinWrappers()
            webCoroutines()
        }
    }

    droid {
        targetModifier = {

        }
        dependencies = {
            activityFragment()
            androidCoroutines()
//            lifecycle()
            extensions()
            firebase(project)
        }
    }

    darwin {
        dependencies = {
            
        }
    }
    server {
        targetModifier = {
            jvmToolchain(11)
        }
        dependencies = {
            vertx()
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.core")
}


//dependencies {
//    add("kspCommonMainMetadata", project(":flatinvoker-compiler"))
//}

tasks.register<Copy>("copyDokka") {
    group = "reaktor"
    dependsOn("dokkaHtml")
    from("$buildDir/dokka/html")
    into("docs/${project.name}")
}

