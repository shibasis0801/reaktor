package dev.shibasis.reaktor.experiments.cloudflarehello.supabase

import dev.shibasis.reaktor.cloudflare.CloudflareRequest
import dev.shibasis.reaktor.graph.service.Response
import kotlinx.serialization.Serializable

@Serializable
data class SupabaseCatalogEntry(
    val schema: String,
    val name: String,
    val kind: String,
)

@Serializable
data class SupabaseTableCount(
    val schema: String,
    val name: String,
    val count: Int,
)

@Serializable
data class SupabaseConnectionInfo(
    val database: String,
    val currentUser: String,
    val serverTime: String,
)

@Serializable
class SupabaseOverviewRequest : CloudflareRequest()

@Serializable
class SupabaseOverviewResponse(
    val message: String,
    val routes: List<String>,
    val notes: List<String>,
) : Response()

@Serializable
class SupabaseStatusRequest : CloudflareRequest()

@Serializable
class SupabaseStatusResponse(
    val connection: SupabaseConnectionInfo,
    val objects: List<SupabaseCatalogEntry>,
    val counts: List<SupabaseTableCount>,
) : Response()
