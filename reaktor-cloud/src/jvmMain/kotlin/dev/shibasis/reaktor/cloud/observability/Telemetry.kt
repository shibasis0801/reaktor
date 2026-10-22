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
    val tracesDatasourceUid: String,
    val clickhouseDs: DataSource?,
    val portsDashboard: Dashboard,
)

const val DefaultTracesDatasourceUid = "reaktor-tempo"

fun telemetry(ctx: Context, g: GrafanaContext): TelemetryOutputs {
    val cfg = ctx.config("telemetry")

    val tracesDatasourceUid = cfg.get("traces_datasource_uid").orElse(DefaultTracesDatasourceUid)

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
            .configJson(Output.of(resource("/dashboards/reaktor-ports.json")))
            .folder(g.folder.uid())
            .build(),
        g.opts,
    )

    return TelemetryOutputs(tracesDatasourceUid, clickhouseDs, portsDashboard)
}
