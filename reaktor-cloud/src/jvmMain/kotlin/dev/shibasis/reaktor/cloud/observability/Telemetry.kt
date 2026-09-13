package dev.shibasis.reaktor.cloud.observability

import com.google.gson.Gson
import com.pulumi.Context
import com.pulumi.core.Output
import com.pulumi.grafana.oss.Dashboard
import com.pulumi.grafana.oss.DashboardArgs
import com.pulumi.grafana.oss.DataSource
import com.pulumi.grafana.oss.DataSourceArgs

private val gson = Gson()

class TelemetryOutputs(
    val tempoDs: DataSource,
    val clickhouseDs: DataSource?,
    val portsDashboard: Dashboard,
)

/**
 * Traces from Reaktor runtimes: the Tempo datasource that reads what `reaktor-telemetry`
 * exports, the ClickHouse datasource that holds the analytics half, and the dashboard built on
 * the `reaktor.*` semantic convention.
 *
 * Required stack config (nothing here reads an environment variable or a file):
 * ```
 * pulumi config set        telemetry:tempo_url        https://tempo-prod-<zone>.grafana.net/tempo
 * pulumi config set        telemetry:tempo_user       <numeric tempo instance id>
 * pulumi config set --secret telemetry:access_token   <cloud access policy token>
 * # optional, when ClickHouse is reachable from the Grafana stack
 * pulumi config set        telemetry:clickhouse_host  clickhouse.internal
 * pulumi config set        telemetry:clickhouse_user  grafana_reader
 * pulumi config set --secret telemetry:clickhouse_password <password>
 * ```
 *
 * The matching export side is `OtlpEndpoint` in `reaktor-telemetry`, which posts to
 * `https://otlp-gateway-<zone>.grafana.net/otlp/v1/traces` with the same access token.
 */
fun telemetry(ctx: Context, g: GrafanaContext): TelemetryOutputs {
    val cfg = ctx.config("telemetry")
    val accessToken = cfg.requireSecret("access_token")

    val tempoDs = DataSource(
        "reaktor-tempo",
        DataSourceArgs.builder()
            .type("tempo")
            .name("reaktor-traces")
            .url(cfg.require("tempo_url"))
            .basicAuthEnabled(true)
            .basicAuthUsername(cfg.require("tempo_user"))
            .jsonDataEncoded(
                gson.toJson(
                    mapOf(
                        // Node and port ids are stable architectural identities, so they are safe
                        // as span-to-metric dimensions. Activation id is deliberately absent.
                        "tracesToLogsV2" to mapOf("customQuery" to true, "query" to "{\$\${__tags}}"),
                        "nodeGraph" to mapOf("enabled" to true),
                        "search" to mapOf("hide" to false),
                    ),
                ),
            )
            .secureJsonDataEncoded(accessToken.applyValue { t -> gson.toJson(mapOf("basicAuthPassword" to t)) })
            .build(),
        g.opts,
    )

    // ClickHouse is optional: it only exists once the stack can reach the cluster.
    val clickhouseHost = cfg.get("clickhouse_host").orElse("")
    val clickhouseDs = if (clickhouseHost.isBlank()) null else DataSource(
        "reaktor-clickhouse",
        DataSourceArgs.builder()
            .type("grafana-clickhouse-datasource")
            .name("reaktor-analytics")
            .jsonDataEncoded(
                gson.toJson(
                    mapOf(
                        "host" to clickhouseHost,
                        "port" to cfg.get("clickhouse_port").orElse("9000").toInt(),
                        "protocol" to "native",
                        "secure" to true,
                        "username" to cfg.get("clickhouse_user").orElse("grafana_reader"),
                        "defaultDatabase" to cfg.get("clickhouse_database").orElse("default"),
                    ),
                ),
            )
            // Required once a host is configured: a read-only datasource with no password is
            // either broken or unauthenticated, and both should fail at plan time.
            .secureJsonDataEncoded(
                cfg.requireSecret("clickhouse_password")
                    .applyValue { password -> gson.toJson(mapOf("password" to password)) },
            )
            .build(),
        g.opts,
    )

    val portsDashboard = Dashboard(
        "reaktor-ports",
        DashboardArgs.builder()
            .configJson(
                tempoDs.uid().applyValue { uid ->
                    resource("/dashboards/reaktor-ports.json").replace("__TEMPO_DS__", uid)
                },
            )
            .folder(g.folder.uid())
            .build(),
        g.opts,
    )

    return TelemetryOutputs(tempoDs, clickhouseDs, portsDashboard)
}
