package dev.shibasis.reaktor.auth.supabase

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth

val supabase = createSupabaseClient(
    "https://<your-supabase-url>.supabase.co",
    "<your-supabase-key>"
) {
    install(Auth)
}