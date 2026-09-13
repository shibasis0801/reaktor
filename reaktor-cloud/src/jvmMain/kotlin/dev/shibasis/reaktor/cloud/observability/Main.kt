package dev.shibasis.reaktor.cloud.observability

import com.pulumi.Pulumi
import com.pulumi.core.Output

/**
 * Mirrors index.ts — wires the modules and exports the datasource/dashboard UIDs that the
 * Cloud pane and deep-links consume (cloudflareDashboardUid, supabaseDashboardUid, ...).
 */
fun main() {
    Pulumi.run { ctx ->
        val g = grafana(ctx)
        val supabase = supabase(ctx, g)
        val cloudflare = cloudflare(ctx, g)
        val pubsub = pubsub(ctx, g)
        val k3s = k3s(ctx)
        val telemetry = telemetry(ctx, g)

        ctx.export("folderUid", g.folder.uid())
        ctx.export("supabaseDatasourceUid", supabase.postgresDs.uid())
        ctx.export("supabaseDashboardUid", supabase.overviewDashboard.uid())
        ctx.export("cloudflareDatasourceUid", cloudflare.infinityDs.uid())
        ctx.export("cloudflareDashboardUid", cloudflare.workersDashboard.uid())
        ctx.export("gcpDatasourceUid", pubsub.gcpDs.uid())
        ctx.export("tracesDatasourceUid", Output.of(telemetry.tracesDatasourceUid))
        ctx.export("portsDashboardUid", telemetry.portsDashboard.uid())
        telemetry.clickhouseDs?.let { ctx.export("clickhouseDatasourceUid", it.uid()) }
        ctx.export("pubsubDashboardUid", pubsub.pubsubDashboard.uid())
        k3s?.let { ctx.export("k3sMonitoringStatus", it.status()) }
    }
}
