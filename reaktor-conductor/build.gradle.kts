import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.common.commonSerialization
import dev.shibasis.dependeasy.verification.classpathProperty

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.conductor") {
        common {
            dependencies {
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
                // SupervisedProcessExecutor: argv without a shell, independent stdout/stderr line
                // streams, process-tree ownership, timeouts, redaction, and plan fingerprints.
                api(project(":reaktor-tooling"))
            }
        }
    }
    val main = "dev.shibasis.reaktor.conductor.cli.ConductorCliKt"
    jvmEntryPoint("conduct", main)
    jvmLauncher("prepareAgentLauncher", main, "agent-launcher", provider { listOf("workspace") })
    jvmDistribution("agentDist", main, "reaktor-agent")

    tasks.named<Test>("jvmTest") {
        val compilation = kotlin.jvm().compilations.getByName("test")
        inputs.property("serviceTest", providers.environmentVariable("REAKTOR_AGENT_SERVICE_TEST").getOrElse("0"))
        inputs.property("nativeActivityTest", providers.environmentVariable("REAKTOR_NATIVE_ACTIVITY_TEST").getOrElse("0"))
        classpathProperty("reaktor.conductor.testClasspath", files(compilation.output.allOutputs, compilation.runtimeDependencyFiles))
    }
}
