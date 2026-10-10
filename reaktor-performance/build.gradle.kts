import dev.shibasis.dependeasy.common.commonSerialization

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    javascript("performanceTypeScript").verify("checkPerformanceTypeScript", "ts/tsconfig.json")
    module("dev.shibasis.reaktor.performance") {
        common {
            dependencies {
                api(project(":reaktor-core"))
                commonSerialization()
            }
        }
        web {}
        android {}
        apple {}
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
