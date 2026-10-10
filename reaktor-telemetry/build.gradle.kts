import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonNetworking

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.telemetry") {
        common {
            dependencies {
                api(project(":reaktor-core"))
                commonNetworking()
                api(project(":reaktor-graph-runtime"))
                api(Versions.Telemetry.Api)
                api(Versions.Telemetry.Noop)
                implementation(Versions.Telemetry.Implementation)
            }
            testDependencies {
                implementation(Versions.Kotlin.KtorMock)
            }
        }

        android {
            dependencies {
                api(project.dependencies.platform(Versions.Android.FirebaseBom))
                api(Versions.Android.FirebaseKotlinCrashlytics)
            }
        }
        apple {}
        web {}
        jvm {}

        kotlin {
            applyDefaultHierarchyTemplate()
        }
    }


    // The JVM test runner scans every class in the test source set; restrict it to test classes.
    tasks.withType<Test>().configureEach {
        filter {
            isFailOnNoMatchingTests = false
            includeTestsMatching("*Test")
        }
    }
}
