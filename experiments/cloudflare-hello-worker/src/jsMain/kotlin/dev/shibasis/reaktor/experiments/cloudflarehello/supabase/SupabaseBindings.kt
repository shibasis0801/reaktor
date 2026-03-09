package dev.shibasis.reaktor.experiments.cloudflarehello.supabase

import dev.shibasis.reaktor.cloudflare.postgres

object SupabaseBindings {
    val database = postgres("SUPABASE")
}
