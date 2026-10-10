import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.common.commonSerialization

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.tooling") {
        common {
            dependencies {
                api(project(":reaktor-code"))
                commonCoroutines()
                commonSerialization(protobuf = false)
            }
        }
        web {}
        android {}
        apple {}

        jvm {
            bytecode = 21
            dependencies {
                api(project(":reaktor-mcp"))
                implementation(Versions.Tooling.SnakeYaml)
                implementation(Versions.Tooling.Lsp)
                implementation(Versions.Tooling.KubernetesClient)
                implementation(Versions.Google.Auth)
                implementation(Versions.Data.Postgres)
                implementation(Versions.Data.Neo4j)
                // Google's own client for the adb server protocol, coroutines-native. Replaces
                // shelling out to the `adb` binary: device tracking, shellV2, sync and forwarding are
                // all streaming operations that a one-shot argv grammar cannot express.
                implementation(Versions.Tooling.Adb)
                // The idb companion's gRPC service, so Apple targets need only the companion binary
                // rather than the Python client on top of it.
                api(project(":reaktor-idb"))
            }
            testDependencies {
                implementation(Versions.Data.MockWebServer)
            }
        }
    }


    dependencyBoundary("verifyToolingBoundary") {
        description = "Keeps external tool adapters independent of GUI and closed product modules."
        forbidGroupPrefixes("androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko", "ai.bestbuds")
        forbidModules("kernel", "engine", "app", "design", "reaktor-ui", "reaktor-graph")
        reason.set("Tooling has frontend or product dependencies")
    }

    // The JVM test runner scans every class in the test source set; restrict it to test classes.
    tasks.withType<Test>().configureEach {
        filter {
            isFailOnNoMatchingTests = false
            includeTestsMatching("*Test")
        }
    }
}
