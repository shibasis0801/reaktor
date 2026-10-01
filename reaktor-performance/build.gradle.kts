import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.Version

plugins {
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
            api(project(":reaktor-service"))
            commonSerialization()
        }
    }
    web {}
    droid {}
    darwin {}
    server {}
    applyDefaultHierarchyTemplate()
    sourceSets {
        val mobileMain by creating {
            dependsOn(commonMain.get())
            dependencies { implementation("sh.measure:measure-kmp:${Version.MeasureKmp}") }
        }
        androidMain.get().dependsOn(mobileMain)
        iosMain.get().dependsOn(mobileMain)
        commonTest.dependencies { implementation("io.ktor:ktor-client-mock:${Version.Ktor}") }
    }
}

android {
    defaults("dev.shibasis.reaktor.performance")
}

// The JVM test runner scans every class in the test source set; restrict it to test classes.
tasks.withType<Test>().configureEach {
    filter {
        isFailOnNoMatchingTests = false
        includeTestsMatching("*Test")
    }
}
