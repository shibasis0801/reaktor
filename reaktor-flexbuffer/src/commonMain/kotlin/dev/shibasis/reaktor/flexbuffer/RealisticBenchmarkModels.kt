package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.flexbuffer.core.Struct
import kotlinx.serialization.Serializable

// ─── Realistic benchmark models ───
// These represent actual production data patterns, not synthetic type-coverage tests.
// Each exercises a different hot path: string-heavy, number-heavy, deeply nested,
// wide-and-flat, collection-dominated, or mixed.

// 1. UserProfile — typical social app entity. String-heavy, moderate nesting.
@Struct
@Serializable
data class BenchAddress(
    val street: String,
    val city: String,
    val state: String,
    val zip: String,
    val country: String
)

@Struct
@Serializable
data class BenchUserProfile(
    val id: Long,
    val username: String,
    val displayName: String,
    val email: String,
    val bio: String,
    val avatarUrl: String,
    val followerCount: Int,
    val followingCount: Int,
    val postCount: Int,
    val verified: Boolean,
    val createdAtEpochMs: Long,
    val tags: List<String>,
    val settings: Map<String, String>,
    val address: BenchAddress
)

// 2. ApiResponse — paginated REST response wrapping a list of items.
// Tests: large typed list of nested objects, metadata map.
@Struct
@Serializable
data class BenchProduct(
    val id: Long,
    val name: String,
    val description: String,
    val priceInCents: Int,
    val currency: String,
    val inStock: Boolean,
    val rating: Float,
    val reviewCount: Int,
    val categoryIds: List<Int>,
    val imageUrls: List<String>
)

@Struct
@Serializable
data class BenchApiResponse(
    val status: Int,
    val message: String,
    val page: Int,
    val pageSize: Int,
    val totalItems: Int,
    val totalPages: Int,
    val items: List<BenchProduct>,
    val metadata: Map<String, String>
)

// 3. EventLog — telemetry/analytics payload. Number-heavy with string maps.
@Struct
@Serializable
data class BenchEventLog(
    val eventId: String,
    val eventType: String,
    val timestampMs: Long,
    val userId: Long,
    val sessionId: String,
    val durationMs: Int,
    val properties: Map<String, String>,
    val metrics: Map<String, Double>,
    val tags: List<String>,
    val parentEventId: String,
    val success: Boolean
)

// 4. ChatThread — messaging thread with multiple messages.
// Tests: list of nested objects with variable-length strings.
@Struct
@Serializable
data class BenchChatMessage(
    val id: Long,
    val senderId: Long,
    val text: String,
    val timestampMs: Long,
    val edited: Boolean,
    val replyToId: Long,
    val reactions: Map<String, Int>
)

@Struct
@Serializable
data class BenchChatThread(
    val threadId: Long,
    val title: String,
    val participantIds: List<Long>,
    val messages: List<BenchChatMessage>,
    val lastReadTimestamps: Map<String, Long>,
    val pinned: Boolean,
    val muted: Boolean
)

// 5. ConfigSnapshot — app config with nested feature flags.
// Tests: deeply nested maps, boolean-heavy, mixed primitives.
@Struct
@Serializable
data class BenchFeatureFlag(
    val enabled: Boolean,
    val rolloutPercent: Int,
    val allowedUserIds: List<Long>,
    val metadata: Map<String, String>
)

@Struct
@Serializable
data class BenchConfigSnapshot(
    val version: Int,
    val environment: String,
    val buildNumber: Long,
    val features: Map<String, BenchFeatureFlag>,
    val thresholds: Map<String, Double>,
    val endpoints: Map<String, String>,
    val enabledModules: List<String>,
    val debugMode: Boolean
)

// 6. TimeSeriesChunk — numeric-heavy payload (sensor data, financial ticks).
// Tests: large primitive arrays, minimal strings.
@Struct
@Serializable
data class BenchTimeSeriesChunk(
    val seriesId: String,
    val startEpochMs: Long,
    val intervalMs: Int,
    val values: List<Double>,
    val timestamps: List<Long>,
    val min: Double,
    val max: Double,
    val mean: Double,
    val count: Int
)

// ─── Factory functions with realistic data ───

object BenchmarkData {

    fun userProfile() = BenchUserProfile(
        id = 8847291L,
        username = "shibasis.patnaik",
        displayName = "Shibasis Patnaik",
        email = "shibasis@reaktor.build",
        bio = "Building cross-platform infrastructure. KMP enthusiast. Reaktor framework author.",
        avatarUrl = "https://cdn.reaktor.build/avatars/8847291/profile_400x400.webp",
        followerCount = 2847,
        followingCount = 312,
        postCount = 891,
        verified = true,
        createdAtEpochMs = 1609459200000L,
        tags = listOf("kotlin", "multiplatform", "android", "ios", "react", "infrastructure"),
        settings = mapOf(
            "theme" to "dark", "language" to "en", "timezone" to "Asia/Kolkata",
            "notifications" to "all", "privacy" to "friends", "two_factor" to "enabled"
        ),
        address = BenchAddress(
            street = "42 MG Road, Indiranagar",
            city = "Bengaluru",
            state = "Karnataka",
            zip = "560038",
            country = "IN"
        )
    )

    fun apiResponse() = BenchApiResponse(
        status = 200,
        message = "OK",
        page = 1,
        pageSize = 20,
        totalItems = 847,
        totalPages = 43,
        items = (1..20).map { i ->
            BenchProduct(
                id = 10000L + i,
                name = "Product ${('A'.code + i - 1).toChar()} — Premium Edition",
                description = "High quality product with advanced features. Built for professionals who demand the best. Ships worldwide with tracking.",
                priceInCents = 1999 + (i * 500),
                currency = "USD",
                inStock = i % 3 != 0,
                rating = 3.5f + (i % 5) * 0.3f,
                reviewCount = 42 + i * 17,
                categoryIds = listOf(100 + i, 200 + i % 5, 300 + i % 3),
                imageUrls = listOf(
                    "https://cdn.example.com/products/${10000 + i}/main.webp",
                    "https://cdn.example.com/products/${10000 + i}/thumb.webp"
                )
            )
        },
        metadata = mapOf(
            "cache_hit" to "true", "region" to "ap-south-1",
            "request_id" to "req_a1b2c3d4e5f6", "latency_ms" to "12"
        )
    )

    fun eventLog() = BenchEventLog(
        eventId = "evt_7f3a9b2c4d5e",
        eventType = "page_view",
        timestampMs = 1716307200000L,
        userId = 8847291L,
        sessionId = "sess_x9y8z7w6v5u4",
        durationMs = 3420,
        properties = mapOf(
            "page" to "/dashboard/analytics",
            "referrer" to "https://reaktor.build/docs",
            "browser" to "Chrome/125.0",
            "os" to "macOS 15.4",
            "device" to "desktop",
            "screen" to "2560x1440",
            "locale" to "en-US"
        ),
        metrics = mapOf(
            "time_to_first_byte" to 42.5,
            "dom_content_loaded" to 187.3,
            "largest_contentful_paint" to 312.8,
            "cumulative_layout_shift" to 0.02,
            "first_input_delay" to 8.1,
            "total_blocking_time" to 45.7
        ),
        tags = listOf("web", "dashboard", "authenticated", "premium"),
        parentEventId = "evt_parent_abc123",
        success = true
    )

    fun chatThread() = BenchChatThread(
        threadId = 55012L,
        title = "Reaktor FlexBuffer Performance",
        participantIds = listOf(8847291L, 1234567L, 9876543L, 5555555L),
        messages = (1..15).map { i ->
            BenchChatMessage(
                id = 100000L + i,
                senderId = listOf(8847291L, 1234567L, 9876543L, 5555555L)[i % 4],
                text = when (i % 5) {
                    0 -> "We should focus on the encode path first — that's where most of the overhead is."
                    1 -> "Agreed. The decode side is already close to C++ because it's just pointer arithmetic on the buffer."
                    2 -> "I ran the benchmarks on M2: FlexCoder is 4.6x faster than JSON on ComplexCase."
                    3 -> "Nice! What about the collection-heavy case? That's where real apps spend most time."
                    else -> "Let me push the latest numbers. The presorted endMap optimization alone saved 30%."
                },
                timestampMs = 1716307200000L + i * 60000L,
                edited = i == 7,
                replyToId = if (i > 3 && i % 4 == 0) 100000L + i - 2 else 0L,
                reactions = if (i % 3 == 0) mapOf("thumbsup" to 2, "fire" to 1) else emptyMap()
            )
        },
        lastReadTimestamps = mapOf(
            "8847291" to 1716308100000L,
            "1234567" to 1716307800000L,
            "9876543" to 1716307500000L,
            "5555555" to 1716307200000L
        ),
        pinned = true,
        muted = false
    )

    fun configSnapshot() = BenchConfigSnapshot(
        version = 47,
        environment = "production",
        buildNumber = 20260521001L,
        features = mapOf(
            "dark_mode" to BenchFeatureFlag(true, 100, emptyList(), mapOf("since" to "v2.1")),
            "ai_suggestions" to BenchFeatureFlag(true, 75, listOf(8847291L, 1234567L), mapOf("model" to "claude-4", "max_tokens" to "1024")),
            "realtime_collab" to BenchFeatureFlag(false, 0, emptyList(), mapOf("blocked_by" to "INFRA-2847")),
            "export_pdf" to BenchFeatureFlag(true, 100, emptyList(), emptyMap()),
            "beta_editor" to BenchFeatureFlag(true, 25, listOf(8847291L), mapOf("variant" to "B", "experiment" to "editor_v3")),
            "push_notifications" to BenchFeatureFlag(true, 90, emptyList(), mapOf("provider" to "firebase"))
        ),
        thresholds = mapOf(
            "max_upload_mb" to 50.0,
            "rate_limit_rpm" to 120.0,
            "session_timeout_min" to 30.0,
            "cache_ttl_sec" to 300.0,
            "retry_backoff_ms" to 1000.0
        ),
        endpoints = mapOf(
            "api" to "https://api.reaktor.build/v2",
            "ws" to "wss://ws.reaktor.build/v2",
            "cdn" to "https://cdn.reaktor.build",
            "auth" to "https://auth.reaktor.build"
        ),
        enabledModules = listOf("core", "graph", "flow", "ui", "auth", "media", "io", "db"),
        debugMode = false
    )

    fun timeSeriesChunk(): BenchTimeSeriesChunk {
        val count = 256
        return BenchTimeSeriesChunk(
            seriesId = "sensor_temp_rack_42",
            startEpochMs = 1716307200000L,
            intervalMs = 1000,
            values = (0 until count).map { 22.5 + (it % 20) * 0.1 + (it % 7) * 0.05 },
            timestamps = (0 until count).map { 1716307200000L + it * 1000L },
            min = 22.5,
            max = 24.85,
            mean = 23.42,
            count = count
        )
    }
}
