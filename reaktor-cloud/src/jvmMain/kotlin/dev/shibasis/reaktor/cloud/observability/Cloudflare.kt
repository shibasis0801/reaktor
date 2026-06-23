package dev.shibasis.reaktor.cloud.observability

import com.google.gson.Gson
import com.pulumi.Context
import com.pulumi.core.Output
import com.pulumi.grafana.oss.Dashboard
import com.pulumi.grafana.oss.DashboardArgs
import com.pulumi.grafana.oss.DataSource
import com.pulumi.grafana.oss.DataSourceArgs

private val gson = Gson()

class CloudflareOutputs(val infinityDs: DataSource, val workersDashboard: Dashboard)

/**
 * Mirrors src/cloudflare.ts — Cloudflare analytics reached via the Infinity datasource
 * (yesoreyeram-infinity-datasource) hitting the CF GraphQL API, plus the workers dashboard.
 */
fun cloudflare(ctx: Context, g: GrafanaContext): CloudflareOutputs {
    val cfg = ctx.config("cloudflare")
    val accountId = cfg.require("account_id")
    val apiToken = cfg.requireSecret("api_token")
    val zoneId = cfg.get("zone_id").orElse("")

    val infinityDs = DataSource(
        "cloudflare-infinity",
        DataSourceArgs.builder()
            .type("yesoreyeram-infinity-datasource")
            .name("cloudflare-graphql")
            .url("https://api.cloudflare.com/client/v4/graphql")
            .basicAuthEnabled(false)
            .jsonDataEncoded(
                gson.toJson(
                    mapOf(
                        "auth_method" to "bearerToken",
                        "customHealthCheckEnabled" to false,
                        "oauthPassThru" to false,
                    ),
                ),
            )
            .secureJsonDataEncoded(apiToken.applyValue { t -> gson.toJson(mapOf("bearerToken" to t)) })
            .build(),
        g.opts,
    )

    val dashboardJson = resource("/dashboards/cloudflare-workers.json")
    val workersDashboard = Dashboard(
        "cloudflare-workers",
        DashboardArgs.builder()
            .configJson(
                Output.all(infinityDs.uid(), Output.of(accountId), Output.of(zoneId))
                    .applyValue { v ->
                        dashboardJson
                            .replace("__INFINITY_DS__", v[0])
                            .replace("__ACCOUNT_ID__", v[1])
                            .replace("__ZONE_ID__", v[2])
                    },
            )
            .folder(g.folder.uid())
            .build(),
        g.opts,
    )

    return CloudflareOutputs(infinityDs, workersDashboard)
}
