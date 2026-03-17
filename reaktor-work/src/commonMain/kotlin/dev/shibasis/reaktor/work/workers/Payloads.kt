package dev.shibasis.reaktor.work.workers

import dev.mattramotar.meeseeks.runtime.TaskPayload
import kotlinx.serialization.Serializable

// Data sync - periodic background sync of user data
@Serializable
data class SyncPayload(val syncType: String = "full") : TaskPayload

// Cache cleanup - remove expired cache entries
@Serializable
data object CacheCleanupPayload : TaskPayload

// Analytics upload - batch upload analytics events
@Serializable
data object AnalyticsUploadPayload : TaskPayload

// Media upload - upload pending media files
@Serializable
data class MediaUploadPayload(val mediaId: String) : TaskPayload

// Database maintenance - vacuum, compact, optimize
@Serializable
data object DatabaseMaintenancePayload : TaskPayload

// Content prefetch - preload content for offline use
@Serializable
data class PrefetchPayload(val contentType: String = "feed") : TaskPayload

// Heartbeat - periodic health check / keep-alive
@Serializable
data object HeartbeatPayload : TaskPayload

// Token refresh - refresh auth tokens before expiry
@Serializable
data object TokenRefreshPayload : TaskPayload

// Log upload - upload collected logs to server
@Serializable
data object LogUploadPayload : TaskPayload

// Notification sync - sync notification state with server
@Serializable
data object NotificationSyncPayload : TaskPayload
