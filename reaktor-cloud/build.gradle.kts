import dev.shibasis.dependeasy.configuration.FrameworkOptions
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.*

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.cloud", targets = Targets.Android + Targets.Apple + Targets.Jvm) {
    val grafana = pulumiSdk("grafana", Versions.Server.GrafanaProvider, "github://api.github.com/pulumiverse")
    dependencies {
        common {
            api(projects.reaktorCore)
            api(projects.reaktorGraph)
            api(projects.reaktorTooling)
        }
        jvm {
            implementation(grafana)
            implementation(Versions.Server.Pulumi)
            implementation(Versions.Server.PulumiKubernetes)
            implementation(Versions.Tooling.GrpcNettyShaded)
            implementation(Versions.Data.Gson)
            implementation(Versions.Tooling.Jsr305)
            implementation(Versions.Tooling.SnakeYaml)
            implementation(Versions.Kotlin.SerializationJson)
        }
    }
    val run by program(sources = FileSet(listOf("reaktor-cloud/src/jvmMain/kotlin/dev/shibasis/reaktor/cloud/observability/Main.kt")))
    val verifyWrangler by program(
        environment = ProgramEnvironment(inputs = listOf(ProgramInput(FrameworkOptions.WranglerDirectory, whenBlank = ProgramFallback.BuildRoot))),
        sources = FileSet(listOf("reaktor-cloud/src/jvmMain/kotlin/dev/shibasis/reaktor/cloud/WranglerVerify.kt")))
}
