import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.work", targets = Targets.Android + Targets.Apple + Targets.Web + Targets.Jvm) {
    val resources by webResources()
    dependencies {
        common { api(projects.reaktorCore); api(projects.reaktorGraphRuntime); api(projects.reaktorAuthCore); implementation(Versions.Data.Okio) }
        commonTest { implementation(Versions.Kotlin.CoroutinesTest) }
        android { api(Versions.Android.WorkManager) }
        androidInstrumentedTest { implementation(Versions.Android.TestRunner) }
        jvm { api(Versions.Server.Quartz) }
        jvmTest { implementation(Versions.Data.SqlDelightSQLite) }
    }
    val verifyWorkRuntimeBoundary by boundary(
        groupPrefixes = listOf("androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko", "ai.bestbuds"),
        groupFragments = listOf("meeseeks"),
        modules = listOf("reaktor-graph", "reaktor-ui", "engine", "kernel"),
        reason = "Work has vendor or frontend dependencies")
}

tasks.withType<Test>().configureEach {
    providers.gradleProperty("reaktorWorkTestJdbcUrl").orNull?.let { environment("REAKTOR_WORK_TEST_JDBC_URL", it) }
}
