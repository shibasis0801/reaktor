package dev.shibasis.reaktor.cloud

import dev.shibasis.reaktor.cloud.api.CloudApis
import dev.shibasis.reaktor.cloud.api.CloudDeclarations
import dev.shibasis.reaktor.cloud.docker.DockerContexts
import dev.shibasis.reaktor.cloud.docker.DockerEngine
import dev.shibasis.reaktor.cloud.grafana.GrafanaClient
import dev.shibasis.reaktor.cloud.http.text
import dev.shibasis.reaktor.cloud.kubernetes.KubernetesApply
import dev.shibasis.reaktor.cloud.kubernetes.KubernetesJvmClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test

class CloudClientsLiveTest {
    private val workspace = System.getenv("REAKTOR_CLOUD_LIVE_WORKSPACE")?.let(::File)

    @Test fun readsDockerOnTheCurrentContext() {
        workspace ?: return
        val docker = DockerEngine(DockerContexts.current())
        val containers = docker.containers()
        val version = docker.version()
        println("docker ${docker.context.name}: engine ${version.text("Version")} api ${version.text("ApiVersion")} · ${containers.size} containers, " +
            "${containers.count { it.text("State") == "running" }} running · ports ${docker.publishedPorts()} · compose ${docker.composeProjects().keys.sorted()}")
    }

    @Test fun readsGrafana() = runBlocking {
        val root = workspace ?: return@runBlocking
        val site = CloudDeclarations.of(root).grafana ?: return@runBlocking
        val grafana = GrafanaClient.of(root, site)
        val health = grafana.health()
        println("grafana ${health.text("version")} database ${health.text("database")} · ${grafana.dashboards().size} dashboards, ${grafana.folders().size} folders, " +
            "${grafana.dataSources().size} data sources, alert rules ${runCatching { grafana.alertRules().size }.getOrElse { "unreadable: ${it.message}" }}")
    }

    @Test fun readsThroughEveryApiSurface() = runBlocking {
        val root = workspace ?: return@runBlocking
        val apis = CloudApis(root)
        listOf("docker:SystemInfo", "grafana:search", "kubernetes:listCoreV1Namespace", "supabase:v1-list-all-projects").forEach { key ->
            val response = runCatching { apis.call(key, JsonObject(emptyMap())) }
            println("api $key: " + response.fold({ "HTTP ${it.status} · ${it.text.length} bytes" }, { "failed: ${it.message?.take(200)}" }) +
                " · ${runCatching { apis.surface(CloudApis.surfaceOf(key)).operations.size }.getOrDefault(0)} operations · ${apis.signIn(CloudApis.surfaceOf(key))}")
        }
    }

    @Test fun plansTheObservabilityBackendsWithADryRun() {
        val root = workspace ?: return
        val kubeconfig = File(root, "cloud/k3s/kubeconfig").takeIf { it.isFile } ?: return
        InfrastructureSession().use { session ->
            KubernetesApply(KubernetesJvmClient(kubeconfig, session)).plan(File(root, "cloud/k3s/observability/backends.yaml").readText()).forEach { plan ->
                println("plan ${plan.target}: live ${plan.exists} at ${plan.liveVersion} · ${plan.changes.size} changes ${plan.changes.take(12).map { it.path }}")
            }
        }
    }
}
