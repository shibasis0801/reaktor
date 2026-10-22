package dev.shibasis.reaktor.cloud.observability

import com.pulumi.Context
import com.pulumi.core.Output
import com.pulumi.kubernetes.Provider as K8sProvider
import com.pulumi.kubernetes.ProviderArgs as K8sProviderArgs
import com.pulumi.kubernetes.helm.v3.Release
import com.pulumi.kubernetes.helm.v3.ReleaseArgs
import com.pulumi.kubernetes.helm.v3.inputs.RepositoryOptsArgs
import com.pulumi.resources.CustomResourceOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

fun k3s(ctx: Context): Release? {
    val cfg = ctx.config("k3s")
    val enabled = cfg.getBoolean("enabled").orElse(false)
    if (!enabled) return null

    val clusterName = cfg.require("cluster_name")
    val kubeconfigPath = cfg.require("kubeconfig_path")

    val promUrl = cfg.require("prometheus_url")
    val lokiUrl = cfg.require("loki_url")

    val kubeconfig = File(kubeconfigPath).readText()
    val k8sProvider = K8sProvider("k3s-cluster", K8sProviderArgs.builder().kubeconfig(kubeconfig).build())

    val valuesTemplate = resource("/helm/k8s-monitoring-values.yaml")
    val rendered = valuesTemplate
        .replace("\${CLUSTER_NAME}", clusterName)
        .replace("\${PROM_URL}", promUrl)
        .replace("\${LOKI_URL}", lokiUrl)
    val values: Output<Map<String, Any>> = Output.of(Yaml().load<Map<String, Any>>(rendered))

    return Release(
        "k8s-monitoring",
        ReleaseArgs.builder()
            .chart("k8s-monitoring")
            .version("2.0.16")
            .namespace("monitoring")
            .createNamespace(true)
            .repositoryOpts(RepositoryOptsArgs.builder().repo("https://grafana.github.io/helm-charts").build())
            .values(values)
            .atomic(false)
            .timeout(600)
            .build(),
        CustomResourceOptions.builder().provider(k8sProvider).build(),
    )
}
