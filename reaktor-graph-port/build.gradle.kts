import dev.shibasis.dependeasy.Versions
plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    javascript("graphPortTypeScript") {
        kotlinLibraries("reaktor-graph-port")
        verify("checkGraphPortTypeScript", "ts/tsconfig.json")
    }
    module("dev.shibasis.reaktor.graph.port") {
        common {
            dependencies {
                api(Versions.Kotlin.AtomicFu)
                api(project(":reaktor-core"))
            }
        }
        android {}
        apple {}
        web {}
        jvm {}
    }


    // The JVM test runner scans every class in the test source set, so a plain fixture class
    // (no @Test methods) fails with initializationError. Restrict it to test classes by name.
    tasks.withType<Test>().configureEach {
        filter {
            isFailOnNoMatchingTests = false
            includeTestsMatching("*Test")
        }
    }
}
