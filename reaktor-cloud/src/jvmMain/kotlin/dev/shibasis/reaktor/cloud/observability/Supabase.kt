package dev.shibasis.reaktor.cloud.observability

import com.google.gson.Gson
import com.pulumi.Context
import com.pulumi.grafana.oss.Dashboard
import com.pulumi.grafana.oss.DashboardArgs
import com.pulumi.grafana.oss.DataSource
import com.pulumi.grafana.oss.DataSourceArgs

private val gson = Gson()

class SupabaseOutputs(val postgresDs: DataSource, val overviewDashboard: Dashboard)

/** Mirrors src/supabase.ts — the Supabase Postgres datasource, optional privileged Prometheus, and overview dashboard. */
fun supabase(ctx: Context, g: GrafanaContext): SupabaseOutputs {
    val cfg = ctx.config("supabase")
    val projectRef = cfg.require("project_ref")
    val region = cfg.require("region")
    val dbPassword = cfg.requireSecret("db_password")

    val postgresDs = DataSource(
        "supabase-postgres",
        DataSourceArgs.builder()
            .type("postgres")
            .name("supabase-postgres")
            .url("aws-0-$region.pooler.supabase.com:5432")
            .databaseName("postgres")
            .username("postgres.$projectRef")
            .secureJsonDataEncoded(dbPassword.applyValue { p -> gson.toJson(mapOf("password" to p)) })
            .jsonDataEncoded(
                gson.toJson(
                    mapOf(
                        "sslmode" to "require",
                        "postgresVersion" to 1500,
                        "maxOpenConns" to 10,
                        "maxIdleConns" to 2,
                        "timescaledb" to false,
                    ),
                ),
            )
            .build(),
        g.opts,
    )

    // Only created on Pro+ plans that expose the privileged metrics endpoint. Whether to
    // create the resource is a synchronous decision, so gate on the plain `get` presence
    // check and pull the value as a secret Output via `requireSecret`.
    if (cfg.get("service_role_key").isPresent) {
        val key = cfg.requireSecret("service_role_key")
        DataSource(
            "supabase-prometheus",
            DataSourceArgs.builder()
                .type("prometheus")
                .name("supabase-prometheus")
                .url("https://$projectRef.supabase.co/customer/v1/privileged/metrics")
                .basicAuthEnabled(true)
                .basicAuthUsername("service_role")
                .secureJsonDataEncoded(key.applyValue { k -> gson.toJson(mapOf("basicAuthPassword" to k)) })
                .jsonDataEncoded(gson.toJson(mapOf("httpMethod" to "GET")))
                .build(),
            g.opts,
        )
    }

    val dashboardJson = resource("/dashboards/supabase-overview.json")
    val overviewDashboard = Dashboard(
        "supabase-overview",
        DashboardArgs.builder()
            .configJson(postgresDs.uid().applyValue { uid -> dashboardJson.replace("__POSTGRES_DS__", uid) })
            .folder(g.folder.uid())
            .build(),
        g.opts,
    )

    return SupabaseOutputs(postgresDs, overviewDashboard)
}
