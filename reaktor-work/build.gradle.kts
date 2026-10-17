import dev.shibasis.dependeasy.Versions
plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.work") {
        common {
            dependencies {
                api(projects.reaktorCore)
                api(projects.reaktorGraphRuntime)
                api(projects.reaktorAuthCore)
            }
            testDependencies {
                implementation(Versions.Kotlin.CoroutinesTest)
            }
        }

        android {
            dependencies {
                api(Versions.Android.WorkManager)
            }
        }
        apple {}
        web {}

        jvm {
            dependencies { api(Versions.Server.Quartz) }
            testDependencies {
                implementation(Versions.Data.SqlDelightSQLite)
            }
        }
    }


    dependencyBoundary("verifyWorkRuntimeBoundary") {
        forbidGroupPrefixes("androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko", "ai.bestbuds")
        forbidGroupFragments("meeseeks")
        forbidModules("reaktor-graph", "reaktor-ui", "engine", "kernel")
        reason.set("Work has vendor or frontend dependencies")
    }
}
