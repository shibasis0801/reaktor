package dev.shibasis.reaktor.experiments.cloudflarehello.supabase

import dev.shibasis.reaktor.cloudflare.hyperdrive

object SupabaseBindings {
    val database = hyperdrive("SUPABASE")
}
