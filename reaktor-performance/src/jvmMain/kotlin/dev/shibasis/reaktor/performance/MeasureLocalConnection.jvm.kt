package dev.shibasis.reaktor.performance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class MeasureLocalConnection(
    @SerialName("api_url") val apiUrl: String,
    @SerialName("ingest_url") val ingestUrl: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_at") val expiresAt: Long,
    val apps: List<MeasureLocalApp>,
    @SerialName("dashboard_url") val dashboardUrl: String = MeasureLocalServer.DashboardUrl,
    @SerialName("team_id") val teamId: String? = null,
)

@Serializable
class MeasureLocalApp(
    val id: String,
    val name: String,
    @SerialName("api_key") val ingestKey: String,
)
