import dev.shibasis.dependeasy.native.*
import dev.shibasis.dependeasy.native.NativePlatform
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.common.commonSerialization

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    nativeLibrary {
        platforms(NativePlatform.Android, NativePlatform.Darwin, NativePlatform.Host)
        abis(NativeAbi.X86, NativeAbi.Arm64, NativeAbi.X86_64)
    }
    javascript("performanceTypeScript").verify("checkPerformanceTypeScript", "ts/tsconfig.json")
    module("dev.shibasis.reaktor.performance") {
        common {
            dependencies {
                api(projects.reaktorCore)
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
