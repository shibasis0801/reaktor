import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.cloudflare") {
        common {
            dependencies {
                api(projects.reaktorAuthCore)
                api(projects.reaktorCore)
                api(projects.reaktorService)
                api(projects.reaktorGraphRuntime)
                api(projects.reaktorIo)
                api(projects.reaktorSecrets)
            }
        }
        android {}
        apple {}

        web {
            dependencies {
                api(npm("hono", Versions.Web.Hono))
                api(npm("partyserver", Versions.Web.PartyServer))
                api(npm("postgres", Versions.Web.Postgres))
            }
            testDependencies {
                implementation(projects.reaktorWork)
                implementation(npm("miniflare", Versions.Web.Miniflare))
            }
        }

        kotlin {

            js {
                nodejs { testTask { useMocha { timeout = "60s" } } }
            }
        }
    }


    dependencyBoundary("verifyCloudflareRuntimeBoundary", "jsCompileClasspath") {
        moduleSuffix.set("-js")
        forbidGroupPrefixes("androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko", "ai.bestbuds")
        forbidModules("reaktor-auth", "reaktor-graph", "reaktor-ui", "engine", "kernel")
        reason.set("Cloudflare hosts must remain headless")
    }
}
