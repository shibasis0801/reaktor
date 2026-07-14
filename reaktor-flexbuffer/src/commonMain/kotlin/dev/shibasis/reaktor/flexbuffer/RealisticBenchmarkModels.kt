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

// 7. NotificationInbox - social/mobile notification feed.
// Tests: nested event rows, action lists, metadata maps, read state.
@Struct
@Serializable
data class BenchNotificationItem(
    val id: Long,
    val type: String,
    val title: String,
    val body: String,
    val actorUserId: Long,
    val route: String,
    val createdAtMs: Long,
    val read: Boolean,
    val priority: Int,
    val actions: List<String>,
    val metadata: Map<String, String>
)

@Struct
@Serializable
data class BenchNotificationInbox(
    val userId: Long,
    val unreadCount: Int,
    val lastSyncMs: Long,
    val cursor: String,
    val channelSettings: Map<String, String>,
    val items: List<BenchNotificationItem>
)

// 8. OrderHistory - commerce account order page.
// Tests: multiple nested collections, money fields, status strings.
@Struct
@Serializable
data class BenchOrderLine(
    val lineId: Long,
    val sku: String,
    val name: String,
    val quantity: Int,
    val unitPriceCents: Long,
    val taxCents: Long,
    val discountCents: Long,
    val fulfillmentState: String
)

@Struct
@Serializable
data class BenchOrder(
    val orderId: Long,
    val merchantId: Long,
    val createdAtMs: Long,
    val status: String,
    val currency: String,
    val totalCents: Long,
    val shippingCity: String,
    val trackingNumber: String,
    val lines: List<BenchOrderLine>
)

@Struct
@Serializable
data class BenchOrderHistory(
    val userId: Long,
    val preferredCurrency: String,
    val loyaltyTier: String,
    val paymentMethods: List<String>,
    val summaryCents: Map<String, Long>,
    val orders: List<BenchOrder>
)

// 9. MediaLibrary - user album/media manifest.
// Tests: URL-heavy assets, dimensions, labels, variant maps.
@Struct
@Serializable
data class BenchMediaAsset(
    val assetId: Long,
    val ownerId: Long,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val cdnUrl: String,
    val blurHash: String,
    val createdAtMs: Long,
    val labels: List<String>,
    val variants: Map<String, String>
)

@Struct
@Serializable
data class BenchMediaLibrary(
    val albumId: Long,
    val ownerId: Long,
    val title: String,
    val coverAssetId: Long,
    val sharedWithUserIds: List<Long>,
    val permissions: Map<String, String>,
    val assets: List<BenchMediaAsset>
)

// 10. SearchResultsPage - app/server search response.
// Tests: snippets, scores, facets, attributes, pagination metadata.
@Struct
@Serializable
data class BenchSearchHit(
    val id: Long,
    val title: String,
    val snippet: String,
    val url: String,
    val score: Double,
    val source: String,
    val updatedAtMs: Long,
    val highlights: List<String>,
    val attributes: Map<String, String>
)

@Struct
@Serializable
data class BenchSearchFacet(
    val name: String,
    val count: Int,
    val selected: Boolean
)

@Struct
@Serializable
data class BenchSearchResultsPage(
    val query: String,
    val total: Int,
    val tookMs: Int,
    val page: Int,
    val pageSize: Int,
    val hits: List<BenchSearchHit>,
    val facets: List<BenchSearchFacet>,
    val experimentFlags: Map<String, String>
)

// 11. WorkoutSession - wearable/health activity sync.
// Tests: medium numeric sample stream plus device/session metadata.
@Struct
@Serializable
data class BenchWorkoutSample(
    val offsetSec: Int,
    val heartRate: Int,
    val cadence: Int,
    val latitude: Double,
    val longitude: Double,
    val elevationM: Double,
    val speedMps: Double
)

@Struct
@Serializable
data class BenchWorkoutSession(
    val sessionId: String,
    val userId: Long,
    val sport: String,
    val startMs: Long,
    val durationSec: Int,
    val distanceM: Double,
    val calories: Int,
    val avgHeartRate: Int,
    val device: String,
    val zones: Map<String, Int>,
    val samples: List<BenchWorkoutSample>
)

// 12. BankingLedger - fintech account activity page.
// Tests: large transaction list, money fields, category strings, and maps.
@Struct
@Serializable
data class BenchBankTransaction(
    val id: Long,
    val postedAtMs: Long,
    val amountCents: Long,
    val type: String,
    val merchant: String,
    val category: String,
    val description: String,
    val tags: List<String>
)

@Struct
@Serializable
data class BenchBankingLedger(
    val accountId: Long,
    val holderName: String,
    val currency: String,
    val openedAtMs: Long,
    val balances: Map<String, Long>,
    val riskFlags: List<String>,
    val statements: Map<String, String>,
    val transactions: List<BenchBankTransaction>
)

// 13. RideHistory - rideshare trip archive.
// Tests: geo points, status strings, ratings map, and route vectors.
@Struct
@Serializable
data class BenchGeoPoint(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long
)

@Struct
@Serializable
data class BenchRideTrip(
    val id: Long,
    val driverId: Long,
    val status: String,
    val pickup: String,
    val dropoff: String,
    val startedAtMs: Long,
    val distanceM: Double,
    val fareCents: Long,
    val routePoints: List<BenchGeoPoint>
)

@Struct
@Serializable
data class BenchRideHistory(
    val riderId: Long,
    val region: String,
    val userTier: String,
    val driverRatings: Map<String, Double>,
    val trips: List<BenchRideTrip>
)

// 14. ProjectBoardSnapshot - product/work tracking board.
// Tests: columns, task comments, labels, members, and metadata.
@Struct
@Serializable
data class BenchTaskComment(
    val authorId: Long,
    val body: String,
    val createdAtMs: Long
)

@Struct
@Serializable
data class BenchProjectTask(
    val taskId: Long,
    val title: String,
    val status: String,
    val priority: Int,
    val assigneeId: Long,
    val dueAtMs: Long,
    val labels: List<String>,
    val comments: List<BenchTaskComment>
)

@Struct
@Serializable
data class BenchProjectColumn(
    val name: String,
    val wipLimit: Int,
    val tasks: List<BenchProjectTask>
)

@Struct
@Serializable
data class BenchProjectBoardSnapshot(
    val boardId: Long,
    val name: String,
    val updatedAtMs: Long,
    val members: List<Long>,
    val metadata: Map<String, String>,
    val columns: List<BenchProjectColumn>
)

// 15. DocumentCorpus - workspace document/revision bundle.
// Tests: deeply nested revisions with repeated text segments.
@Struct
@Serializable
data class BenchDocSegment(
    val ordinal: Int,
    val kind: String,
    val text: String,
    val wordCount: Int
)

@Struct
@Serializable
data class BenchDocRevision(
    val revisionId: Long,
    val authorId: Long,
    val createdAtMs: Long,
    val checksum: String,
    val segments: List<BenchDocSegment>
)

@Struct
@Serializable
data class BenchWorkspaceDocument(
    val documentId: Long,
    val title: String,
    val authorId: Long,
    val visibility: String,
    val tags: List<String>,
    val revisions: List<BenchDocRevision>
)

@Struct
@Serializable
data class BenchDocumentCorpus(
    val workspaceId: Long,
    val ownerId: Long,
    val indexVersion: Long,
    val permissions: Map<String, String>,
    val documents: List<BenchWorkspaceDocument>
)

// 16. SecurityAuditTrail - auth/security audit export.
// Tests: event-heavy log with metadata and summary counters.
@Struct
@Serializable
data class BenchSecurityAuditEvent(
    val eventId: Long,
    val actorId: Long,
    val type: String,
    val resource: String,
    val ipAddress: String,
    val timestampMs: Long,
    val riskScore: Double,
    val success: Boolean,
    val metadata: Map<String, String>
)

@Struct
@Serializable
data class BenchSecurityAuditTrail(
    val appId: String,
    val policyVersion: String,
    val generatedAtMs: Long,
    val summary: Map<String, Int>,
    val events: List<BenchSecurityAuditEvent>
)

// 17. GraphSnapshot - graph/flow editor or relationship graph.
// Tests: many nodes, many edges, small property maps.
@Struct
@Serializable
data class BenchGraphNode(
    val id: Long,
    val kind: String,
    val labels: List<String>,
    val properties: Map<String, String>
)

@Struct
@Serializable
data class BenchGraphEdge(
    val fromId: Long,
    val toId: Long,
    val kind: String,
    val strength: Double,
    val properties: Map<String, String>
)

@Struct
@Serializable
data class BenchGraphSnapshot(
    val graphId: Long,
    val version: Long,
    val properties: Map<String, String>,
    val nodes: List<BenchGraphNode>,
    val edges: List<BenchGraphEdge>
)

// 18. RecommendationFeed - ranked cards/feed response.
// Tests: scores, explanation rows, metadata maps, and action lists.
@Struct
@Serializable
data class BenchRecommendationReason(
    val feature: String,
    val weight: Double
)

@Struct
@Serializable
data class BenchRecommendationCard(
    val itemId: Long,
    val itemType: String,
    val title: String,
    val score: Double,
    val reasons: List<BenchRecommendationReason>,
    val actions: List<String>,
    val metadata: Map<String, String>
)

@Struct
@Serializable
data class BenchRecommendationFeed(
    val feedId: String,
    val userId: Long,
    val generatedAtMs: Long,
    val modelVersion: String,
    val cards: List<BenchRecommendationCard>
)

// 19. GameWorldState - multiplayer/world snapshot.
// Tests: entities with inventory, positions, attributes, and quests.
@Struct
@Serializable
data class BenchWorldPosition(
    val x: Double,
    val y: Double,
    val z: Double
)

@Struct
@Serializable
data class BenchInventoryItem(
    val itemId: Long,
    val quantity: Int,
    val rarity: String
)

@Struct
@Serializable
data class BenchWorldEntity(
    val entityId: Long,
    val kind: String,
    val state: String,
    val position: BenchWorldPosition,
    val inventory: List<BenchInventoryItem>,
    val attributes: Map<String, Double>
)

@Struct
@Serializable
data class BenchWorldQuest(
    val questId: Long,
    val title: String,
    val completed: Boolean,
    val objectives: Map<String, Int>
)

@Struct
@Serializable
data class BenchGameWorldState(
    val worldId: Long,
    val playerId: Long,
    val tick: Long,
    val entities: List<BenchWorldEntity>,
    val quests: List<BenchWorldQuest>
)

// 20. IoTFleetSnapshot - device fleet telemetry.
// Tests: nested readings, metrics maps, tags, and alert rows.
@Struct
@Serializable
data class BenchDeviceReading(
    val name: String,
    val value: Double,
    val quality: Int,
    val timestampMs: Long
)

@Struct
@Serializable
data class BenchIoTDevice(
    val deviceId: String,
    val firmware: String,
    val online: Boolean,
    val tags: List<String>,
    val metrics: Map<String, Double>,
    val readings: List<BenchDeviceReading>
)

@Struct
@Serializable
data class BenchFleetAlert(
    val deviceId: String,
    val severity: Int,
    val message: String,
    val timestampMs: Long
)

@Struct
@Serializable
data class BenchIoTFleetSnapshot(
    val fleetId: String,
    val region: String,
    val sampledAtMs: Long,
    val devices: List<BenchIoTDevice>,
    val alerts: List<BenchFleetAlert>
)

// 21. CRMPortfolio - sales/account workspace.
// Tests: contacts, opportunities, tags, and account lists.
@Struct
@Serializable
data class BenchCRMContact(
    val name: String,
    val email: String,
    val role: String,
    val lastContactMs: Long
)

@Struct
@Serializable
data class BenchCRMOpportunity(
    val title: String,
    val stage: String,
    val amountCents: Long,
    val probability: Double,
    val closeDateMs: Long
)

@Struct
@Serializable
data class BenchCRMAccount(
    val accountId: Long,
    val name: String,
    val industry: String,
    val tags: List<String>,
    val contacts: List<BenchCRMContact>,
    val opportunities: List<BenchCRMOpportunity>
)

@Struct
@Serializable
data class BenchCRMPortfolio(
    val ownerUserId: Long,
    val region: String,
    val pipeline: String,
    val snapshotMs: Long,
    val accounts: List<BenchCRMAccount>
)

// 22. TravelItinerary - travel app bundle.
// Tests: mixed booking rows, time fields, nested flight segments.
@Struct
@Serializable
data class BenchFlightSegment(
    val from: String,
    val to: String,
    val cabin: String,
    val seat: String,
    val durationMin: Int
)

@Struct
@Serializable
data class BenchFlightBooking(
    val flightNo: String,
    val carrier: String,
    val departureAirport: String,
    val arrivalAirport: String,
    val departureMs: Long,
    val arrivalMs: Long,
    val segments: List<BenchFlightSegment>
)

@Struct
@Serializable
data class BenchHotelBooking(
    val name: String,
    val city: String,
    val confirmation: String,
    val checkInMs: Long,
    val checkOutMs: Long,
    val nightlyRateCents: Long
)

@Struct
@Serializable
data class BenchTravelActivity(
    val name: String,
    val city: String,
    val scheduledMs: Long,
    val costCents: Long,
    val tags: List<String>
)

@Struct
@Serializable
data class BenchTravelItinerary(
    val itineraryId: Long,
    val travelerId: Long,
    val flights: List<BenchFlightBooking>,
    val hotels: List<BenchHotelBooking>,
    val activities: List<BenchTravelActivity>
)

// 23. CourseRoster - education/cohort state.
// Tests: students, grades maps, submissions, modules, and lessons.
@Struct
@Serializable
data class BenchLesson(
    val lessonId: Long,
    val title: String,
    val durationMin: Int
)

@Struct
@Serializable
data class BenchCourseModule(
    val moduleId: Long,
    val title: String,
    val weight: Double,
    val lessons: List<BenchLesson>
)

@Struct
@Serializable
data class BenchSubmission(
    val moduleId: Long,
    val score: Double,
    val submittedAtMs: Long,
    val late: Boolean
)

@Struct
@Serializable
data class BenchStudentRecord(
    val studentId: Long,
    val name: String,
    val email: String,
    val lastActiveMs: Long,
    val grades: Map<String, Double>,
    val submissions: List<BenchSubmission>
)

@Struct
@Serializable
data class BenchCourseRoster(
    val courseId: Long,
    val term: String,
    val instructors: List<Long>,
    val modules: List<BenchCourseModule>,
    val students: List<BenchStudentRecord>
)

// 24. ShipmentBatch - logistics tracking export.
// Tests: shipment events, package dimensions, and status strings.
@Struct
@Serializable
data class BenchPackageInfo(
    val packageId: Long,
    val weightG: Int,
    val widthCm: Int,
    val heightCm: Int
)

@Struct
@Serializable
data class BenchShipmentEvent(
    val code: String,
    val location: String,
    val timestampMs: Long
)

@Struct
@Serializable
data class BenchShipment(
    val shipmentId: Long,
    val status: String,
    val destinationCity: String,
    val serviceLevel: String,
    val packages: List<BenchPackageInfo>,
    val events: List<BenchShipmentEvent>
)

@Struct
@Serializable
data class BenchShipmentBatch(
    val batchId: Long,
    val warehouseId: String,
    val carrier: String,
    val createdAtMs: Long,
    val shipments: List<BenchShipment>
)

// 25. MarketDataSnapshot - trading/market data slice.
// Tests: order book levels, quote ticks, and numeric density.
@Struct
@Serializable
data class BenchBookOrder(
    val side: String,
    val level: Int,
    val price: Double,
    val quantity: Long
)

@Struct
@Serializable
data class BenchMarketTick(
    val timestampMs: Long,
    val price: Double,
    val size: Long
)

@Struct
@Serializable
data class BenchInstrumentQuote(
    val symbol: String,
    val bid: Double,
    val ask: Double,
    val lastPrice: Double,
    val orders: List<BenchBookOrder>,
    val ticks: List<BenchMarketTick>
)

@Struct
@Serializable
data class BenchMarketDataSnapshot(
    val market: String,
    val sequence: Long,
    val timestampMs: Long,
    val instruments: List<BenchInstrumentQuote>
)

// 26. SocialGraphDelta - relationship and interaction sync delta.
// Tests: users, edges, interactions, metadata maps.
@Struct
@Serializable
data class BenchDeltaUser(
    val userId: Long,
    val username: String,
    val displayName: String,
    val followerCount: Int,
    val interests: List<String>
)

@Struct
@Serializable
data class BenchDeltaEdge(
    val fromUserId: Long,
    val toUserId: Long,
    val relation: String,
    val strength: Double
)

@Struct
@Serializable
data class BenchDeltaInteraction(
    val userId: Long,
    val targetId: Long,
    val type: String,
    val timestampMs: Long,
    val metadata: Map<String, String>
)

@Struct
@Serializable
data class BenchSocialGraphDelta(
    val batchId: Long,
    val generatedAtMs: Long,
    val users: List<BenchDeltaUser>,
    val edges: List<BenchDeltaEdge>,
    val interactions: List<BenchDeltaInteraction>
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
                // Kotlin/JS evaluates Float arithmetic as a JavaScript Number. Force the
                // benchmark fixture through its IEEE-754 binary32 representation so codecs
                // that correctly persist a Float are compared against identical input on
                // every target.
                rating = Float.fromBits((3.5f + (i % 5) * 0.3f).toRawBits()),
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

    fun notificationInbox() = BenchNotificationInbox(
        userId = 8847291L,
        unreadCount = 9,
        lastSyncMs = 1716382800000L,
        cursor = "notif_cursor_2026_05_22_1716382800",
        channelSettings = mapOf(
            "mentions" to "push",
            "messages" to "push",
            "friend_requests" to "push",
            "campaign_updates" to "digest",
            "marketing" to "off"
        ),
        items = (1..30).map { i ->
            BenchNotificationItem(
                id = 900000L + i,
                type = listOf("message", "mention", "friend_request", "campaign_update", "reaction")[i % 5],
                title = when (i % 5) {
                    0 -> "New message in Reaktor performance"
                    1 -> "You were mentioned by Anika"
                    2 -> "Rahul sent a friend request"
                    3 -> "Campaign recommendations refreshed"
                    else -> "Nina reacted to your post"
                },
                body = "Notification $i contains realistic preview text, route hints, and actor metadata for an app inbox.",
                actorUserId = 700000L + i,
                route = "/notifications/${900000 + i}",
                createdAtMs = 1716382800000L - i * 45000L,
                read = i > 9,
                priority = if (i <= 3) 10 else 3 + (i % 4),
                actions = if (i % 3 == 0) listOf("open", "reply", "mute") else listOf("open"),
                metadata = mapOf(
                    "source" to "social-service",
                    "campaign_id" to "cmp_${1200 + i}",
                    "trace_id" to "trace_notif_${i}_a7f9"
                )
            )
        }
    )

    fun orderHistory() = BenchOrderHistory(
        userId = 8847291L,
        preferredCurrency = "USD",
        loyaltyTier = "gold",
        paymentMethods = listOf("visa_4242", "apple_pay", "store_credit"),
        summaryCents = mapOf(
            "lifetime_spend" to 842_390L,
            "refunded" to 19_900L,
            "store_credit" to 8_500L,
            "pending" to 43_250L
        ),
        orders = (1..12).map { order ->
            BenchOrder(
                orderId = 7000000L + order,
                merchantId = 4000L + (order % 4),
                createdAtMs = 1716000000000L - order * 86_400_000L,
                status = listOf("delivered", "processing", "shipped", "returned")[order % 4],
                currency = "USD",
                totalCents = 8_999L + order * 1_750L,
                shippingCity = listOf("Bengaluru", "London", "San Francisco", "Mumbai")[order % 4],
                trackingNumber = "1Z${8847291 + order}BB${1000 + order}",
                lines = (1..4).map { line ->
                    BenchOrderLine(
                        lineId = order * 100L + line,
                        sku = "SKU-${order}-${line}-PRO",
                        name = "Reaktor merch item $line for order $order",
                        quantity = 1 + (line % 2),
                        unitPriceCents = 1_999L + line * 750L,
                        taxCents = 180L + line * 32L,
                        discountCents = if (line == 2) 250L else 0L,
                        fulfillmentState = if (order % 4 == 1) "warehouse" else "complete"
                    )
                }
            )
        }
    )

    fun mediaLibrary() = BenchMediaLibrary(
        albumId = 65012L,
        ownerId = 8847291L,
        title = "Sample launch media",
        coverAssetId = 810001L,
        sharedWithUserIds = listOf(1234567L, 9876543L, 5555555L, 7788990L),
        permissions = mapOf(
            "visibility" to "friends",
            "download" to "owner_only",
            "comments" to "friends",
            "reshare" to "disabled"
        ),
        assets = (1..24).map { i ->
            BenchMediaAsset(
                assetId = 810000L + i,
                ownerId = 8847291L,
                mimeType = if (i % 5 == 0) "video/mp4" else "image/webp",
                width = if (i % 5 == 0) 1920 else 1440,
                height = if (i % 5 == 0) 1080 else 1440,
                durationMs = if (i % 5 == 0) 12_000L + i * 800L else 0L,
                sizeBytes = 180_000L + i * 42_000L,
                cdnUrl = "https://media.example.com/albums/65012/assets/${810000 + i}/original.webp",
                blurHash = "LKO2?U%2Tw=w]~RBVZRi};RPxuwH_$i",
                createdAtMs = 1716307200000L + i * 30_000L,
                labels = listOf("launch", "friends", if (i % 2 == 0) "portrait" else "group", "sample"),
                variants = mapOf(
                    "thumb" to "https://media.example.com/albums/65012/assets/${810000 + i}/thumb.webp",
                    "preview" to "https://media.example.com/albums/65012/assets/${810000 + i}/preview.webp",
                    "original" to "https://media.example.com/albums/65012/assets/${810000 + i}/original.webp"
                )
            )
        }
    )

    fun searchResultsPage() = BenchSearchResultsPage(
        query = "kotlin flexbuffer performance",
        total = 1847,
        tookMs = 23,
        page = 1,
        pageSize = 25,
        hits = (1..25).map { i ->
            BenchSearchHit(
                id = 510000L + i,
                title = "Result $i: FlexBuffer benchmark notes",
                snippet = "Matched Kotlin serialization, C++ parity, accessors, and JSON baselines in document $i.",
                url = "https://docs.reaktor.build/search/results/${510000 + i}",
                score = 1.0 / i + (i % 3) * 0.07,
                source = listOf("docs", "issues", "benchmarks", "pull_requests")[i % 4],
                updatedAtMs = 1716200000000L - i * 3_600_000L,
                highlights = listOf("flexbuffer", "kotlin", "benchmark", "json"),
                attributes = mapOf(
                    "repo" to "reaktor",
                    "module" to "reaktor-flexbuffer",
                    "language" to if (i % 4 == 0) "c++" else "kotlin"
                )
            )
        },
        facets = listOf(
            BenchSearchFacet("docs", 871, true),
            BenchSearchFacet("issues", 324, false),
            BenchSearchFacet("benchmarks", 98, true),
            BenchSearchFacet("pull_requests", 44, false),
            BenchSearchFacet("source", 510, false)
        ),
        experimentFlags = mapOf(
            "ranker" to "hybrid_v3",
            "rerank" to "enabled",
            "snippet_model" to "local",
            "spellcheck" to "enabled"
        )
    )

    fun workoutSession() = BenchWorkoutSession(
        sessionId = "workout_8847291_20260522_run",
        userId = 8847291L,
        sport = "outdoor_run",
        startMs = 1716381000000L,
        durationSec = 3600,
        distanceM = 10_420.5,
        calories = 812,
        avgHeartRate = 148,
        device = "Reaktor Watch Pro",
        zones = mapOf(
            "zone1" to 420,
            "zone2" to 980,
            "zone3" to 1260,
            "zone4" to 760,
            "zone5" to 180
        ),
        samples = (0 until 120).map { i ->
            BenchWorkoutSample(
                offsetSec = i * 30,
                heartRate = 118 + (i % 50),
                cadence = 152 + (i % 18),
                latitude = 12.9716 + i * 0.00008,
                longitude = 77.5946 + i * 0.00006,
                elevationM = 890.0 + (i % 12) * 0.7,
                speedMps = 2.4 + (i % 20) * 0.04
            )
        }
    )

    fun bankingLedger() = BenchBankingLedger(
        accountId = 2300450091L,
        holderName = "Shibasis Patnaik",
        currency = "USD",
        openedAtMs = 1514764800000L,
        balances = mapOf(
            "available" to 842_391_44L,
            "current" to 845_991_44L,
            "pending" to -360_000L,
            "rewards" to 12_450L
        ),
        riskFlags = listOf("trusted_device", "large_transfer_enabled", "international_enabled"),
        statements = mapOf(
            "2026-01" to "https://bank.example.com/statements/2300450091/2026-01.pdf",
            "2026-02" to "https://bank.example.com/statements/2300450091/2026-02.pdf",
            "2026-03" to "https://bank.example.com/statements/2300450091/2026-03.pdf",
            "2026-04" to "https://bank.example.com/statements/2300450091/2026-04.pdf"
        ),
        transactions = (1..80).map { i ->
            BenchBankTransaction(
                id = 88000000L + i,
                postedAtMs = 1714500000000L + i * 3_600_000L,
                amountCents = if (i % 5 == 0) 125_000L + i * 40 else -(1_200L + i * 187L),
                type = if (i % 5 == 0) "credit" else "debit",
                merchant = listOf("Reaktor Cloud", "Kotlin Cafe", "Metro Transit", "Sample Store", "App Marketplace")[i % 5],
                category = listOf("software", "food", "transport", "shopping", "income")[i % 5],
                description = "Ledger transaction $i with merchant, category, statement, and reconciliation metadata.",
                tags = listOf("card_${i % 3}", "budget_${i % 8}", if (i % 5 == 0) "income" else "expense")
            )
        }
    )

    fun rideHistory() = BenchRideHistory(
        riderId = 8847291L,
        region = "in-blr",
        userTier = "platinum",
        driverRatings = mapOf("avg" to 4.82, "last_30_days" to 4.91, "long_trip" to 4.74),
        trips = (1..36).map { trip ->
            BenchRideTrip(
                id = 3100000L + trip,
                driverId = 910000L + (trip % 12),
                status = listOf("completed", "completed", "cancelled", "refunded")[trip % 4],
                pickup = listOf("Indiranagar", "Koramangala", "MG Road", "Whitefield")[trip % 4],
                dropoff = listOf("Airport", "HSR Layout", "Cubbon Park", "Electronic City")[trip % 4],
                startedAtMs = 1716300000000L + trip * 5_400_000L,
                distanceM = 3_400.0 + trip * 480.5,
                fareCents = 420L + trip * 93L,
                routePoints = (0 until 18).map { p ->
                    BenchGeoPoint(
                        latitude = 12.9716 + trip * 0.0003 + p * 0.00004,
                        longitude = 77.5946 + trip * 0.0002 + p * 0.00005,
                        timestampMs = 1716300000000L + trip * 5_400_000L + p * 45_000L
                    )
                }
            )
        }
    )

    fun projectBoardSnapshot() = BenchProjectBoardSnapshot(
        boardId = 120045L,
        name = "Reaktor FlexBuffer hardening",
        updatedAtMs = 1716400000000L,
        members = listOf(8847291L, 1234567L, 9876543L, 5555555L, 7788990L, 1112223L),
        metadata = mapOf("repo" to "reaktor", "module" to "reaktor-flexbuffer", "sprint" to "perf-2026-05"),
        columns = listOf("Backlog", "Ready", "In Progress", "Review", "Done").mapIndexed { columnIndex, column ->
            BenchProjectColumn(
                name = column,
                wipLimit = 5 + columnIndex,
                tasks = (1..10).map { task ->
                    val id = columnIndex * 100L + task
                    BenchProjectTask(
                        taskId = 420000L + id,
                        title = "$column task $task: expand benchmark coverage for payload family $task",
                        status = column.lowercase().replace(" ", "_"),
                        priority = 1 + ((task + columnIndex) % 5),
                        assigneeId = listOf(8847291L, 1234567L, 9876543L, 5555555L)[task % 4],
                        dueAtMs = 1716500000000L + id * 86_400_000L,
                        labels = listOf("perf", "flexbuffer", if (task % 2 == 0) "runtime" else "docs"),
                        comments = (1..4).map { c ->
                            BenchTaskComment(
                                authorId = 700000L + c,
                                body = "Comment $c on task $id discusses benchmark variance, accessors, and follow-up profiling.",
                                createdAtMs = 1716400000000L + c * 60_000L
                            )
                        }
                    )
                }
            )
        }
    )

    fun documentCorpus() = BenchDocumentCorpus(
        workspaceId = 73001L,
        ownerId = 8847291L,
        indexVersion = 2026052203L,
        permissions = mapOf("visibility" to "team", "commenting" to "members", "export" to "admins"),
        documents = (1..16).map { doc ->
            BenchWorkspaceDocument(
                documentId = 600000L + doc,
                title = "FlexBuffer performance design note $doc",
                authorId = 8847291L + (doc % 5),
                visibility = if (doc % 3 == 0) "private" else "team",
                tags = listOf("performance", "serialization", "benchmark", "doc_$doc"),
                revisions = (1..5).map { rev ->
                    BenchDocRevision(
                        revisionId = doc * 1000L + rev,
                        authorId = 8847291L + (rev % 4),
                        createdAtMs = 1716000000000L + doc * 3_600_000L + rev * 600_000L,
                        checksum = "sha256-${doc}-${rev}-a9c2f17e",
                        segments = (1..10).map { seg ->
                            BenchDocSegment(
                                ordinal = seg,
                                kind = if (seg % 4 == 0) "code" else "paragraph",
                                text = "Document $doc revision $rev segment $seg captures migration risk, measured numbers, and detailed implementation context.",
                                wordCount = 14 + seg
                            )
                        }
                    )
                }
            )
        }
    )

    fun securityAuditTrail() = BenchSecurityAuditTrail(
        appId = "sample-prod",
        policyVersion = "rbac-2026.05",
        generatedAtMs = 1716420000000L,
        summary = mapOf("success" to 93, "denied" to 17, "mfa" to 41, "high_risk" to 6),
        events = (1..110).map { i ->
            BenchSecurityAuditEvent(
                eventId = 990000L + i,
                actorId = 8847000L + (i % 25),
                type = listOf("login", "token_refresh", "role_update", "data_export", "permission_denied")[i % 5],
                resource = listOf("profile", "group", "campaign", "message", "admin")[i % 5],
                ipAddress = "203.0.113.${i % 250}",
                timestampMs = 1716420000000L - i * 27_000L,
                riskScore = (i % 100) / 100.0,
                success = i % 7 != 0,
                metadata = mapOf(
                    "provider" to if (i % 2 == 0) "google" else "apple",
                    "device" to "device_${i % 11}",
                    "trace" to "audit_trace_$i"
                )
            )
        }
    )

    fun graphSnapshot() = BenchGraphSnapshot(
        graphId = 440012L,
        version = 2026052201L,
        properties = mapOf("layout" to "dagre", "owner" to "perf-team", "mode" to "analysis"),
        nodes = (1..90).map { i ->
            BenchGraphNode(
                id = i.toLong(),
                kind = listOf("screen", "service", "repository", "actor", "adapter")[i % 5],
                labels = listOf("node_$i", if (i % 2 == 0) "hot" else "cold", "layer_${i % 7}"),
                properties = mapOf("route" to "/node/$i", "module" to "m_${i % 9}", "owner" to "u_${i % 13}")
            )
        },
        edges = (1..150).map { i ->
            BenchGraphEdge(
                fromId = ((i * 7) % 90 + 1).toLong(),
                toId = ((i * 11) % 90 + 1).toLong(),
                kind = listOf("depends_on", "emits", "observes", "navigates")[i % 4],
                strength = 0.1 + (i % 20) / 20.0,
                properties = mapOf("key" to "edge_$i", "priority" to "${i % 5}")
            )
        }
    )

    fun recommendationFeed() = BenchRecommendationFeed(
        feedId = "feed_8847291_home_20260522",
        userId = 8847291L,
        generatedAtMs = 1716421000000L,
        modelVersion = "ranker-hybrid-v5",
        cards = (1..60).map { i ->
            BenchRecommendationCard(
                itemId = 770000L + i,
                itemType = listOf("profile", "group", "event", "campaign", "article")[i % 5],
                title = "Recommended item $i for social graph and interest cluster",
                score = 0.95 - (i * 0.006),
                reasons = listOf(
                    BenchRecommendationReason("mutual_friends", (i % 10) / 10.0),
                    BenchRecommendationReason("topic_overlap", 0.4 + (i % 6) * 0.07),
                    BenchRecommendationReason("recent_activity", 0.2 + (i % 4) * 0.09)
                ),
                actions = listOf("open", "dismiss", if (i % 3 == 0) "follow" else "save"),
                metadata = mapOf("experiment" to "home_v4", "cluster" to "c_${i % 12}", "trace" to "rec_$i")
            )
        }
    )

    fun gameWorldState() = BenchGameWorldState(
        worldId = 9001L,
        playerId = 8847291L,
        tick = 7_420_331L,
        entities = (1..80).map { i ->
            BenchWorldEntity(
                entityId = 500000L + i,
                kind = listOf("npc", "player", "chest", "projectile", "resource")[i % 5],
                state = listOf("idle", "moving", "combat", "despawned")[i % 4],
                position = BenchWorldPosition(x = i * 1.25, y = (i % 9) * 0.5, z = i * -0.75),
                inventory = (1..5).map { slot ->
                    BenchInventoryItem(900000L + i * 10 + slot, slot + i % 3, listOf("common", "rare", "epic")[slot % 3])
                },
                attributes = mapOf("hp" to (100.0 - i % 40), "mana" to (50.0 + i % 30), "speed" to (1.0 + i % 5))
            )
        },
        quests = (1..12).map { q ->
            BenchWorldQuest(
                questId = 10000L + q,
                title = "Quest $q: stabilize the shard",
                completed = q % 4 == 0,
                objectives = mapOf("collect" to q * 2, "visit" to q, "defeat" to q % 5)
            )
        }
    )

    fun iotFleetSnapshot() = BenchIoTFleetSnapshot(
        fleetId = "factory-floor-17",
        region = "ap-south-1",
        sampledAtMs = 1716422000000L,
        devices = (1..64).map { i ->
            BenchIoTDevice(
                deviceId = "device-${1000 + i}",
                firmware = "fw.${2 + i % 3}.${i % 17}",
                online = i % 9 != 0,
                tags = listOf("line_${i % 6}", "zone_${i % 4}", if (i % 2 == 0) "calibrated" else "needs_review"),
                metrics = mapOf("temp" to 32.0 + i % 7, "humidity" to 60.0 + i % 10, "voltage" to 3.1 + i % 3),
                readings = (1..8).map { r ->
                    BenchDeviceReading(
                        name = listOf("temperature", "humidity", "vibration", "pressure")[r % 4],
                        value = 10.0 + i * 0.25 + r,
                        quality = 90 + (r % 10),
                        timestampMs = 1716422000000L + r * 1000L
                    )
                }
            )
        },
        alerts = (1..18).map { a ->
            BenchFleetAlert(
                deviceId = "device-${1000 + (a * 3) % 64}",
                severity = 1 + a % 5,
                message = "Fleet alert $a reports threshold drift and requires operator acknowledgement.",
                timestampMs = 1716422000000L - a * 15_000L
            )
        }
    )

    fun crmPortfolio() = BenchCRMPortfolio(
        ownerUserId = 8847291L,
        region = "emea",
        pipeline = "enterprise",
        snapshotMs = 1716423000000L,
        accounts = (1..28).map { a ->
            BenchCRMAccount(
                accountId = 650000L + a,
                name = "Account $a Global Systems",
                industry = listOf("fintech", "healthcare", "education", "commerce")[a % 4],
                tags = listOf("tier_${a % 3}", "region_${a % 5}", if (a % 2 == 0) "expansion" else "renewal"),
                contacts = (1..5).map { c ->
                    BenchCRMContact(
                        name = "Contact $c Account $a",
                        email = "contact$c.account$a@example.com",
                        role = listOf("cto", "vp_product", "engineer", "finance", "ops")[c % 5],
                        lastContactMs = 1716423000000L - (a * c) * 86_400_000L
                    )
                },
                opportunities = (1..4).map { o ->
                    BenchCRMOpportunity(
                        title = "Opportunity $o for account $a",
                        stage = listOf("discovery", "proposal", "procurement", "closed_won")[o % 4],
                        amountCents = 25_000_00L + a * o * 75_000L,
                        probability = 0.2 + (o * 0.15),
                        closeDateMs = 1716423000000L + o * 2_592_000_000L
                    )
                }
            )
        }
    )

    fun travelItinerary() = BenchTravelItinerary(
        itineraryId = 77881234L,
        travelerId = 8847291L,
        flights = (1..6).map { f ->
            BenchFlightBooking(
                flightNo = "RK${800 + f}",
                carrier = listOf("Indigo", "Vistara", "Emirates", "British Airways")[f % 4],
                departureAirport = listOf("BLR", "DEL", "DXB", "LHR")[f % 4],
                arrivalAirport = listOf("DEL", "DXB", "LHR", "SFO")[f % 4],
                departureMs = 1716500000000L + f * 86_400_000L,
                arrivalMs = 1716500000000L + f * 86_400_000L + 18_000_000L,
                segments = (1..2).map { s ->
                    BenchFlightSegment(
                        from = listOf("BLR", "DEL", "DXB", "LHR")[s % 4],
                        to = listOf("DEL", "DXB", "LHR", "SFO")[s % 4],
                        cabin = if (s == 1) "business" else "economy",
                        seat = "${12 + f}${'A' + s}",
                        durationMin = 120 + f * 30 + s * 15
                    )
                }
            )
        },
        hotels = (1..5).map { h ->
            BenchHotelBooking(
                name = "Hotel $h Reaktor Plaza",
                city = listOf("Delhi", "Dubai", "London", "San Francisco", "Bengaluru")[h % 5],
                confirmation = "CONF-${77881234 + h}",
                checkInMs = 1716500000000L + h * 86_400_000L,
                checkOutMs = 1716500000000L + (h + 2) * 86_400_000L,
                nightlyRateCents = 18_000L + h * 4_500L
            )
        },
        activities = (1..24).map { a ->
            BenchTravelActivity(
                name = "Activity $a city walk and product meetup",
                city = listOf("Delhi", "Dubai", "London", "San Francisco")[a % 4],
                scheduledMs = 1716500000000L + a * 21_600_000L,
                costCents = 1_500L + a * 210L,
                tags = listOf("food", "work", if (a % 2 == 0) "team" else "solo")
            )
        }
    )

    fun courseRoster() = BenchCourseRoster(
        courseId = 900120L,
        term = "2026-summer",
        instructors = listOf(8847291L, 1234567L, 9876543L),
        modules = (1..10).map { m ->
            BenchCourseModule(
                moduleId = 8000L + m,
                title = "Module $m: production Kotlin multiplatform systems",
                weight = 0.05 + m * 0.01,
                lessons = (1..6).map { l ->
                    BenchLesson(lessonId = m * 100L + l, title = "Lesson $l for module $m", durationMin = 12 + l * 4)
                }
            )
        },
        students = (1..72).map { s ->
            BenchStudentRecord(
                studentId = 300000L + s,
                name = "Student $s",
                email = "student$s@manna.edu",
                lastActiveMs = 1716424000000L - s * 3_600_000L,
                grades = mapOf("quiz" to (70.0 + s % 20), "project" to (75.0 + s % 18), "final" to (68.0 + s % 25)),
                submissions = (1..8).map { sub ->
                    BenchSubmission(
                        moduleId = 8000L + sub,
                        score = 65.0 + (s + sub) % 30,
                        submittedAtMs = 1716424000000L - (s + sub) * 600_000L,
                        late = (s + sub) % 11 == 0
                    )
                }
            )
        }
    )

    fun shipmentBatch() = BenchShipmentBatch(
        batchId = 500045L,
        warehouseId = "blr-warehouse-03",
        carrier = "reaktor-logistics",
        createdAtMs = 1716425000000L,
        shipments = (1..48).map { s ->
            BenchShipment(
                shipmentId = 950000L + s,
                status = listOf("created", "in_transit", "delivered", "exception")[s % 4],
                destinationCity = listOf("Bengaluru", "Mumbai", "Delhi", "London", "San Francisco")[s % 5],
                serviceLevel = listOf("standard", "express", "same_day")[s % 3],
                packages = (1..3).map { p ->
                    BenchPackageInfo(
                        packageId = s * 10L + p,
                        weightG = 500 + s * p * 37,
                        widthCm = 20 + p * 3,
                        heightCm = 10 + p * 4
                    )
                },
                events = (1..6).map { e ->
                    BenchShipmentEvent(
                        code = listOf("PICKED", "SORTED", "LOADED", "ARRIVED", "OUT_FOR_DELIVERY", "DELIVERED")[e - 1],
                        location = "hub_${(s + e) % 8}",
                        timestampMs = 1716425000000L + e * 3_600_000L
                    )
                }
            )
        }
    )

    fun marketDataSnapshot() = BenchMarketDataSnapshot(
        market = "NASDAQ",
        sequence = 8800123499L,
        timestampMs = 1716426000000L,
        instruments = (1..32).map { i ->
            BenchInstrumentQuote(
                symbol = listOf("AAPL", "MSFT", "GOOG", "AMZN", "NVDA", "META", "TSLA", "AMD")[i % 8] + i,
                bid = 100.0 + i * 1.25,
                ask = 100.05 + i * 1.25,
                lastPrice = 100.02 + i * 1.25,
                orders = (1..20).map { level ->
                    BenchBookOrder(
                        side = if (level % 2 == 0) "ask" else "bid",
                        level = level,
                        price = 100.0 + i * 1.25 + level * 0.01,
                        quantity = 100L + level * 25L + i
                    )
                },
                ticks = (1..16).map { t ->
                    BenchMarketTick(
                        timestampMs = 1716426000000L + t * 250L,
                        price = 100.0 + i * 1.25 + t * 0.02,
                        size = 10L + t * 3L
                    )
                }
            )
        }
    )

    fun socialGraphDelta() = BenchSocialGraphDelta(
        batchId = 44000012L,
        generatedAtMs = 1716427000000L,
        users = (1..80).map { u ->
            BenchDeltaUser(
                userId = 700000L + u,
                username = "user_$u",
                displayName = "Graph User $u",
                followerCount = 100 + u * 7,
                interests = listOf("kotlin", "music_${u % 5}", "city_${u % 9}", if (u % 2 == 0) "sports" else "art")
            )
        },
        edges = (1..160).map { e ->
            BenchDeltaEdge(
                fromUserId = 700000L + ((e * 7) % 80 + 1),
                toUserId = 700000L + ((e * 13) % 80 + 1),
                relation = listOf("friend", "follow", "blocked", "muted")[e % 4],
                strength = 0.05 + (e % 20) * 0.04
            )
        },
        interactions = (1..140).map { i ->
            BenchDeltaInteraction(
                userId = 700000L + ((i * 3) % 80 + 1),
                targetId = 700000L + ((i * 5) % 80 + 1),
                type = listOf("message", "reaction", "profile_view", "invite")[i % 4],
                timestampMs = 1716427000000L - i * 45_000L,
                metadata = mapOf("source" to "graph-delta", "weight" to "${i % 10}", "trace" to "sgd_$i")
            )
        }
    )
}
