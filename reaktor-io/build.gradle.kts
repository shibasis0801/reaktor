import dev.shibasis.dependeasy.Versions
import com.codingfeline.buildkonfig.compiler.FieldSpec
import com.codingfeline.buildkonfig.gradle.BuildKonfigTask

import dev.shibasis.dependeasy.*
import dev.shibasis.dependeasy.dependencies.useKoin
import dev.shibasis.dependeasy.dependencies.useNetworking
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("dev.shibasis.dependeasy.library")
    id("com.codingfeline.buildkonfig")
}

dependeasy {
    module("dev.shibasis.reaktor.io") {
        common {
            dependencies {
                api(project(":reaktor-core"))
                api(Versions.Kotlin.Io)
            }
            testDependencies {
                implementation(Versions.Kotlin.KtorMock)
            }
        }
        web {}
        android {}
        apple {}
        jvm {
            testDependencies {
                implementation(Versions.Data.MockWebServer)
            }
        }

        kotlin {
            // https://web.dev/articles/origin-private-file-system
            // https://developer.chrome.com/blog/sqlite-wasm-in-the-browser-backed-by-the-origin-private-file-system

            useNetworking()
        }
    }


    dependencies {
        add("kspCommonMainMetadata", project(":reaktor-compiler"))
        add("kspJs", project(":reaktor-compiler"))
    }

    buildkonfig {
        packageName = "dev.shibasis.reaktor.core"
        objectName = "BuildKonfig"

        defaultConfigs {
            buildConfigField(FieldSpec.Type.STRING, "SERVER", providers.gradleProperty("reaktor.server.host").orElse("0.0.0.0").get())
        }
    }

    tasks.getByName("build").dependsOn(tasks.withType<BuildKonfigTask>())

    testClasses("**/*Test.class")
}
