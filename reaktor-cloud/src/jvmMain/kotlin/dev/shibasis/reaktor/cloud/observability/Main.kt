package dev.shibasis.reaktor.cloud.observability

import com.pulumi.Pulumi

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

        ctx.export("folderUid", g.folder.uid())
        ctx.export("supabaseDatasourceUid", supabase.postgresDs.uid())
        ctx.export("supabaseDashboardUid", supabase.overviewDashboard.uid())
        ctx.export("cloudflareDatasourceUid", cloudflare.infinityDs.uid())
        ctx.export("cloudflareDashboardUid", cloudflare.workersDashboard.uid())
        ctx.export("gcpDatasourceUid", pubsub.gcpDs.uid())
        ctx.export("pubsubDashboardUid", pubsub.pubsubDashboard.uid())
        k3s?.let { ctx.export("k3sMonitoringStatus", it.status()) }
    }
}
