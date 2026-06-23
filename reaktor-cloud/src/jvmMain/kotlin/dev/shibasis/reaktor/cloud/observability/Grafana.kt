package dev.shibasis.reaktor.cloud.observability

import com.pulumi.Context
import com.pulumi.grafana.Provider
import com.pulumi.grafana.ProviderArgs
import com.pulumi.grafana.oss.Folder
import com.pulumi.grafana.oss.FolderArgs
import com.pulumi.resources.CustomResourceOptions

/** Mirrors src/grafana.ts — the shared Grafana provider + the root folder every module hangs off. */
class GrafanaContext(val provider: Provider, val folder: Folder) {
    val opts: CustomResourceOptions = CustomResourceOptions.builder().provider(provider).build()
}

fun grafana(ctx: Context): GrafanaContext {
    val cfg = ctx.config()
    val provider = Provider(
        "grafana",
        ProviderArgs.builder()
            .url(cfg.require("grafana_url"))
            .auth(cfg.requireSecret("grafana_service_account_token"))
            .build(),
    )
    val folder = Folder(
        "reaktor-folder",
        FolderArgs.builder().title("Reaktor").build(),
        CustomResourceOptions.builder().provider(provider).build(),
    )
    return GrafanaContext(provider, folder)
}
