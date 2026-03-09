package dev.shibasis.reaktor.experiments.cloudflarehello.supabase

import dev.shibasis.reaktor.cloudflare.postgres
import dev.shibasis.reaktor.graph.service.GetHandler
import dev.shibasis.reaktor.graph.service.Service

class SupabaseService: Service() {
    init {
        GetHandler<SupabaseOverviewRequest, SupabaseOverviewResponse>("/") {
            SupabaseOverviewResponse(
                message = "Supabase/Postgres demo powered by Cloudflare Hyperdrive and postgres.js",
                routes = listOf(
                    "GET /supabase",
                    "GET /supabase/status",
                ),
                notes = listOf(
                    "Uses the same Hyperdrive plus postgres.js access pattern as bestbuds workers",
                    "Inspects bestbuds-related tables and views in Supabase",
                    "Returns live row counts for a small safe subset of tables",
                ),
            )
        }

        GetHandler<SupabaseStatusRequest, SupabaseStatusResponse>("/status") { request ->
            request.postgres(SupabaseBindings.database) {
                SupabaseRepository(this).inspectBestBudsSurface()
            }
        }
    }
}
