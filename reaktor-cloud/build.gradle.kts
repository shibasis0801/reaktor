import dev.shibasis.dependeasy.configuration.FrameworkOptions
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.common.commonSerialization
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.*

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.cloud", targets = Targets.All) {
    val generateBindings by bindings()
    dependencies {
        common {
            api(projects.reaktorCore)
            api(projects.reaktorService)
            commonCoroutines()
            commonSerialization(protobuf = false)
        }
        jvm {
            api(Versions.Tooling.DependeasyModel)
            implementation(Versions.Kotlin.SerializationJson)
            implementation(Versions.Tooling.SnakeYaml)
            implementation(Versions.Tooling.KubernetesClient)
            implementation(Versions.Google.Auth)
            implementation(Versions.Data.Postgres)
            implementation(Versions.Data.Neo4j)
        }
        web { api(Versions.Kotlin.KarakumRuntime) }
        jvmTest { implementation(Versions.Data.MockWebServer) }
    }
    val verifyWrangler by program(
        environment = ProgramEnvironment(inputs = listOf(ProgramInput(FrameworkOptions.WranglerDirectory, whenBlank = ProgramFallback.BuildRoot))),
        sources = FileSet(listOf("reaktor-cloud/src/jvmMain/kotlin/dev/shibasis/reaktor/cloud/wrangler/WranglerVerify.kt")))
}
