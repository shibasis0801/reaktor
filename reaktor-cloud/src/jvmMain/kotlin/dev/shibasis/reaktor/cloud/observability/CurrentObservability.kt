package dev.shibasis.reaktor.cloud.observability

import com.pulumi.Context
import com.pulumi.core.Output
import com.pulumi.core.annotations.Import
import com.pulumi.grafana.Provider as GrafanaProvider
import com.pulumi.grafana.ProviderArgs as GrafanaProviderArgs
import com.pulumi.grafana.oss.Dashboard
import com.pulumi.grafana.oss.DashboardArgs
import com.pulumi.grafana.oss.Folder
import com.pulumi.grafana.oss.FolderArgs
import com.pulumi.kubernetes.Provider as KubernetesProvider
import com.pulumi.kubernetes.ProviderArgs as KubernetesProviderArgs
import com.pulumi.kubernetes.helm.v3.Release
import com.pulumi.kubernetes.helm.v3.ReleaseArgs
import com.pulumi.kubernetes.helm.v3.inputs.RepositoryOptsArgs
import com.pulumi.resources.CustomResource
import com.pulumi.resources.CustomResourceOptions
import com.pulumi.resources.ResourceArgs
import java.io.File
import kotlinx.serialization.json.*

object CurrentObservability {
    fun declare(context: Context) {
        val config = context.config()
        val workspace = File(requireNotNull(System.getenv("REAKTOR_WORKSPACE_ROOT")))
        val root = workspace.resolve(config.require("sourceDirectory")).canonicalFile
        val paths = Json.parseToJsonElement(config.require("sourceFiles")).jsonArray.map { it.jsonPrimitive.content }
        val files = paths.associateWith { path ->
            val file = root.resolve(path).canonicalFile
            require(file.toPath().startsWith(root.toPath()) && file.isFile)
            file.readText()
        }
        val kubernetes = KubernetesProvider("observability-cluster", KubernetesProviderArgs.builder()
            .kubeconfig(Output.ofSecret(workspace.resolve(config.require("kubeconfig")).readText()))
            .enableConfigMapMutable(true).build())
        val grafana = GrafanaProvider("observability-grafana", GrafanaProviderArgs.builder()
            .url(config.require("grafanaUrl"))
            .auth(Output.ofSecret("admin:" + requireNotNull(System.getenv("GRAFANA_ADMIN_PASSWORD")))).build())
        val folder = Folder("reaktor-bestbuds", FolderArgs.builder()
            .uid("reaktor-bestbuds").title("Reaktor / BestBuds").build(),
            CustomResourceOptions.builder().provider(grafana).importId("reaktor-bestbuds").protect(true).build())
        val dashboards = ObservabilitySources.dashboards(files).map { (uid, document) ->
            Dashboard(uid, DashboardArgs.builder().configJson(document.toString()).folder(folder.uid()).build(),
                CustomResourceOptions.builder().provider(grafana).importId(uid).protect(true).build())
        }
        Release("reaktor-alloy", ReleaseArgs.builder().name("reaktor-alloy").namespace("observability")
            .chart("alloy").version(config.require("alloyChartVersion"))
            .repositoryOpts(RepositoryOptsArgs.builder().repo("https://grafana.github.io/helm-charts").build())
            .values(ObservabilitySources.alloyValues(files)).timeout(180).build(),
            CustomResourceOptions.builder().provider(kubernetes).importId("observability/reaktor-alloy").protect(true).build())
        Release("reaktor-kube-state-metrics", ReleaseArgs.builder().name("reaktor-kube-state-metrics").namespace("observability")
            .chart("kube-state-metrics").version(config.require("kubeStateMetricsChartVersion"))
            .repositoryOpts(RepositoryOptsArgs.builder().repo("https://prometheus-community.github.io/helm-charts").build())
            .values(ObservabilitySources.kubeStateMetricsValues(files)).timeout(180).build(),
            CustomResourceOptions.builder().provider(kubernetes).importId("observability/reaktor-kube-state-metrics").protect(true).build())
        val exporter = workspace.resolve(config.require("cloudflareConfig")).readText()
        val manifests = ObservabilitySources.manifests(files, exporter)
        manifests.forEach { document ->
            @Suppress("UNCHECKED_CAST")
            val metadata = document.getValue("metadata") as Map<String, Any>
            val namespace = metadata.getValue("namespace").toString()
            val name = metadata.getValue("name").toString()
            require(namespace == "observability")
            val version = document.getValue("apiVersion").toString()
            val kind = document.getValue("kind").toString()
            val type = "kubernetes:${if (version == "v1") "core/v1" else version}:$kind"
            val clusterFields = if (kind == "Service")
                listOf("spec.clusterIP", "spec.clusterIPs", "spec.ipFamilies", "spec.ipFamilyPolicy") else emptyList()
            val restartField = when (kind) {
                "StatefulSet" -> listOf("spec.template.metadata.annotations")
                "Deployment" -> listOf("spec.template.metadata.annotations[\"kubectl.kubernetes.io/restartedAt\"]")
                else -> emptyList()
            }
            val claimStatus = if (kind == "StatefulSet") listOf("spec.volumeClaimTemplates[*].status") else emptyList()
            val adoptionMetadata = if (name == "cloudflare-metrics" && kind in setOf("ConfigMap", "Deployment")) {
                @Suppress("UNCHECKED_CAST")
                val annotations = (metadata["annotations"] as? Map<String, Any>).orEmpty()
                metadata + ("annotations" to (annotations + ("pulumi.com/patchForce" to "true")))
            } else metadata
            ManifestResource(type, "${kind.lowercase()}-$name", ManifestArgs(document + ("metadata" to adoptionMetadata)),
                CustomResourceOptions.builder().provider(kubernetes).importId("$namespace/$name").protect(true)
                    .ignoreChanges(clusterFields + restartField + claimStatus).build())
        }
        context.export("folderUid", folder.uid())
        context.export("dashboardUids", Output.of(ObservabilitySources.dashboards(files).keys.sorted()))
        context.export("dashboardCount", Output.of(dashboards.size))
        context.export("manifestCount", Output.of(manifests.size))
    }
}

private class ManifestResource(type: String, name: String, args: ManifestArgs, options: CustomResourceOptions) :
    CustomResource(type, name, args, options)

private class ManifestArgs(document: Map<String, Any>) : ResourceArgs() {
    init { require(document.keys.all { it in setOf("apiVersion", "kind", "metadata", "data", "spec") }) }
    @field:Import(name = "apiVersion", required = true) val apiVersion = document.getValue("apiVersion")
    @field:Import(name = "kind", required = true) val kind = document.getValue("kind")
    @field:Import(name = "metadata", required = true) val metadata = document.getValue("metadata")
    @field:Import(name = "spec") val spec = document["spec"]
    @field:Import(name = "data") val data = document["data"]?.let { Output.ofSecret(it) }
}
