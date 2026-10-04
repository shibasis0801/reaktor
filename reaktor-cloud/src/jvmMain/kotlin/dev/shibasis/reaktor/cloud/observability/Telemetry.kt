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
    /** The Cloud-provisioned Tempo datasource this stack reads; not created here. */
    val tracesDatasourceUid: String,
    val clickhouseDs: DataSource?,
    val portsDashboard: Dashboard,
)

/** Grafana Cloud's own Tempo datasource. Stable across stacks; readOnly, so unmanageable. */
const val GrafanaCloudTracesUid = "grafanacloud-traces"

/**
 * Traces from Reaktor runtimes: the Tempo datasource that reads what `reaktor-telemetry`
 * exports, the ClickHouse datasource that holds the analytics half, and the dashboard built on
 * the `reaktor.*` semantic convention.
 *
 * Required stack config (nothing here reads an environment variable or a file):
 * ```
 * # optional — defaults to Grafana Cloud's own `grafanacloud-traces`
 * pulumi config set        telemetry:traces_datasource_uid grafanacloud-traces
 * # optional, only when ClickHouse is reachable from the Grafana stack
 * pulumi config set        telemetry:clickhouse_host  clickhouse.internal
 * pulumi config set        telemetry:clickhouse_user  grafana_reader
 * pulumi config set --secret telemetry:clickhouse_password <password>
 * ```
 *
 * The matching export side is `OtlpEndpoint` in `reaktor-telemetry`. For this stack
 * (zone `prod-ap-south-1`) that is
 * `https://otlp-gateway-prod-ap-south-1.grafana.net/otlp/v1/traces`.
 */
fun telemetry(ctx: Context, g: GrafanaContext): TelemetryOutputs {
    val cfg = ctx.config("telemetry")

    // Grafana Cloud provisions the Tempo datasource itself as `grafanacloud-traces`, and marks
    // it readOnly — it already has serviceMap, tracesToLogs, tracesToMetrics and tracesToProfiles
    // wired to the sibling Cloud datasources. Creating another one here would duplicate a
    // working, better-connected datasource, so the dashboard references the existing uid.
    val tracesDatasourceUid = cfg.get("traces_datasource_uid").orElse(GrafanaCloudTracesUid)

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

    // The board names its traces source through a `datasource` template variable, so it carries no
    // uid to substitute here and the same file deploys through `sync-grafana.py` — which is what
    // actually put the other seven boards on reaktor.grafana.net — without a templating step.
    val portsDashboard = Dashboard(
        "reaktor-ports",
        DashboardArgs.builder()
            .configJson(Output.of(resource("/dashboards/reaktor-ports.json")))
            .folder(g.folder.uid())
            .build(),
        g.opts,
    )

    return TelemetryOutputs(tracesDatasourceUid, clickhouseDs, portsDashboard)
}
