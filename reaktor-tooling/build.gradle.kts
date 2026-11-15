import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.common.commonSerialization

plugins { id("dev.shibasis.dependeasy.library") }

tasks.withType<org.gradle.language.jvm.tasks.ProcessResources>().matching { it.name == "jvmProcessResources" }.configureEach {
    from(rootProject.file("reaktor-cluster/tooling")) {
        include("gradle-lease", "remote_build.py", "remote_retention.py", "cluster_locations.py")
        into("dev/shibasis/reaktor/tooling/gradle-admission")
    }
}

library(namespace = "dev.shibasis.reaktor.tooling", targets = Targets.All) {
    dependencies {
        common {
            api(Reaktor.Code)
            api(Reaktor.Core)
            api(Reaktor.Cloud)
            commonCoroutines()
            commonSerialization(protobuf = false)
        }
        jvm {
            api(Reaktor.Mcp)
            api(Reaktor.Telemetry)
            api(Versions.Tooling.DependeasyModel)
            implementation(Versions.Tooling.SnakeYaml)
            implementation(Versions.Tooling.Lsp)
            implementation(Versions.Data.Postgres)
            implementation(Versions.Data.Neo4j)
            implementation(Versions.Data.SQLiteJdbc)
            implementation(Versions.Tooling.Adb)
            api(Reaktor.Idb)
        }
        jvmTest { implementation(Versions.Data.MockWebServer) }
    }
    val verifyToolingBoundary by boundary(
        groupPrefixes = listOf("androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko", "ai.bestbuds"),
        modules = listOf("kernel", "engine", "app", "design", "reaktor-ui", "reaktor-graph"),
        reason = "Tooling has frontend or product dependencies",
        description = "Keeps external tool adapters independent of GUI and closed product modules.",
    )
}
