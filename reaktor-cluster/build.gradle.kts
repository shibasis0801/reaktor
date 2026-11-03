import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.common.commonSerialization
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.*

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.cluster", targets = Targets.Android + Targets.Jvm) {
    dependencies {
        common {
            api(Reaktor.Core, Reaktor.GraphRuntime)
            commonCoroutines()
            commonSerialization(protobuf = false)
        }
        jvmTest { implementation(kotlin("test")); implementation(Reaktor.Tooling) }
    }
    val unit by tests(Suite.Jvm)
    val cli by program(environment = ProgramEnvironment(nativeAccess = true))
    val clusterDist by distribution(cli, executable = "reaktor-cluster")
}
