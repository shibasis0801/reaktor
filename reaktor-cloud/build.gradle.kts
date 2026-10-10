import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    val grafana = pulumiSdk("grafana", Versions.Server.GrafanaProvider, "github://api.github.com/pulumiverse")
    module("dev.shibasis.reaktor.cloud") {
        common {
            dependencies {
                api(project(":reaktor-core"))
                api(project(":reaktor-graph"))
                api(project(":reaktor-tooling"))
            }
        }
        android {}
        apple {}

        jvm {
            dependencies {
                implementation(files(grafana))
                implementation(Versions.Server.Pulumi)
                implementation(Versions.Server.PulumiKubernetes)
                implementation(Versions.Data.Gson)
                implementation(Versions.Tooling.Jsr305)
                implementation(Versions.Tooling.SnakeYaml)
                implementation(Versions.Kotlin.SerializationJson)
            }
        }

    }
    jvmEntryPoint("cloudToolE2e", "dev.shibasis.reaktor.cloud.CloudToolE2eKt", compilation = "e2e", verify = true)
    jvmEntryPoint("run", "dev.shibasis.reaktor.cloud.observability.MainKt")
    jvmEntryPoint("verifyWrangler", "dev.shibasis.reaktor.cloud.WranglerVerifyKt") {
        args(providers.gradleProperty("wranglerPath").orElse(rootDir.absolutePath).get())
    }
}
