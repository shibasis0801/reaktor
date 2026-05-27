package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.core.registerGeneratedFlexCoders
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import dev.shibasis.reaktor.flexbuffer.core.FlexCoderRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

/**
 * Realistic benchmark suite: production-representative data structures.
 *
 * Run: ./gradlew :reaktor-flexbuffer:jvmTest --tests "*.RealisticBenchmark" --rerun-tasks
 *
 * Each case exercises a different real-world pattern:
 *   1. UserProfile    — string-heavy social entity with nested address
 *   2. ApiResponse    — paginated response with 20 nested product objects
 *   3. EventLog       — telemetry payload, string+double maps
 *   4. ChatThread     — messaging thread, 15 messages with variable text
 *   5. ConfigSnapshot — feature flags, deeply nested maps of objects
 *   6. TimeSeries     — numeric-heavy, 256-point sensor data chunk
 *   7. NotificationInbox — mobile/social notification feed
 *   8. OrderHistory   — commerce account order page
 *   9. MediaLibrary   — URL-heavy user album manifest
 *  10. SearchResults  — search response with hits and facets
 *  11. WorkoutSession — wearable activity sync payload
 *  12-26. Larger domain cases: finance, rideshare, project boards, docs,
 *       audit logs, graph snapshots, recommendations, game state, IoT,
 *       CRM, travel, education, logistics, market data, and social graph deltas.
 */
class RealisticBenchmark {

    private val json = Json { encodeDefaults = true }
    private val warmup = 500
    private val iterations = 5000

    private inline fun benchUs(label: String, crossinline block: () -> Any): Double {
        repeat(warmup) { block() }
        var result: Any? = null
        val elapsed = measureTime { repeat(iterations) { result = block() } }
        val usPerOp = elapsed.inWholeMicroseconds.toDouble() / iterations
        val totalUs = elapsed.inWholeMicroseconds
        println("  %-40s %4.0f us/op  (%d us total)".format(label, usPerOp, totalUs))
        return usPerOp
    }

    private inline fun <reified T : Any> benchCase(
        label: String,
        data: T,
        fieldCheck: (T) -> Unit
    ) {
        println("\n=== $label ===")

        registerGeneratedFlexCoders()

        val flexEnc = benchUs("FlexCoder encode") { FlexBuffers.encode(data) }
        val flexBytes = FlexBuffers.encode(data)
        val flexDec = benchUs("FlexCoder decode") { FlexBuffers.decode<T>(flexBytes) }

        FlexCoderRegistry.clear()
        val serEnc = benchUs("Serialization encode") { FlexBuffers.encode(serializer<T>(), data) }
        val serBytes = FlexBuffers.encode(serializer<T>(), data)
        val serDec = benchUs("Serialization decode") { FlexBuffers.decode(serializer<T>(), serBytes) }

        val jsonEnc = benchUs("JSON encode") { json.encodeToString(serializer<T>(), data) }
        val jsonStr = json.encodeToString(serializer<T>(), data)
        val jsonDec = benchUs("JSON decode") { json.decodeFromString(serializer<T>(), jsonStr) }

        registerGeneratedFlexCoders()

        println("  ---")
        println("  FlexCoder:      %4.0f us   size: %d B".format(flexEnc + flexDec, flexBytes.size))
        println("  Serialization:  %4.0f us   size: %d B".format(serEnc + serDec, serBytes.size))
        println("  JSON:           %4.0f us   size: %d B".format(jsonEnc + jsonDec, jsonStr.length))

        val decoded = FlexBuffers.decode<T>(flexBytes)
        fieldCheck(decoded)
    }

    @Test
    fun userProfile() = benchCase("UserProfile (string-heavy, nested address)", BenchmarkData.userProfile()) { decoded ->
        assertEquals(8847291L, decoded.id)
        assertEquals("shibasis.patnaik", decoded.username)
        assertEquals(true, decoded.verified)
        assertEquals(6, decoded.tags.size)
        assertEquals("Bengaluru", decoded.address.city)
    }

    @Test
    fun apiResponse() = benchCase("ApiResponse (20 products, paginated)", BenchmarkData.apiResponse()) { decoded ->
        assertEquals(200, decoded.status)
        assertEquals(20, decoded.items.size)
        assertEquals(10001L, decoded.items[0].id)
        assertEquals(3, decoded.items[0].categoryIds.size)
        assertEquals(2, decoded.items[0].imageUrls.size)
    }

    @Test
    fun eventLog() = benchCase("EventLog (telemetry, string+double maps)", BenchmarkData.eventLog()) { decoded ->
        assertEquals("evt_7f3a9b2c4d5e", decoded.eventId)
        assertEquals(7, decoded.properties.size)
        assertEquals(6, decoded.metrics.size)
        assertEquals(true, decoded.success)
    }

    @Test
    fun chatThread() = benchCase("ChatThread (15 messages, reactions)", BenchmarkData.chatThread()) { decoded ->
        assertEquals(55012L, decoded.threadId)
        assertEquals(15, decoded.messages.size)
        assertEquals(4, decoded.participantIds.size)
        assertEquals(true, decoded.pinned)
    }

    @Test
    fun configSnapshot() = benchCase("ConfigSnapshot (feature flags, nested maps)", BenchmarkData.configSnapshot()) { decoded ->
        assertEquals(47, decoded.version)
        assertEquals(6, decoded.features.size)
        assertEquals(true, decoded.features["dark_mode"]?.enabled)
        assertEquals(75, decoded.features["ai_suggestions"]?.rolloutPercent)
        assertEquals(8, decoded.enabledModules.size)
    }

    @Test
    fun timeSeries() = benchCase("TimeSeries (256 doubles + 256 longs)", BenchmarkData.timeSeriesChunk()) { decoded ->
        assertEquals("sensor_temp_rack_42", decoded.seriesId)
        assertEquals(256, decoded.values.size)
        assertEquals(256, decoded.timestamps.size)
        assertEquals(22.5, decoded.min)
    }

    @Test
    fun notificationInbox() = benchCase("NotificationInbox (30 social/mobile notifications)", BenchmarkData.notificationInbox()) { decoded ->
        assertEquals(8847291L, decoded.userId)
        assertEquals(30, decoded.items.size)
        assertEquals(9, decoded.unreadCount)
        assertEquals("push", decoded.channelSettings["mentions"])
    }

    @Test
    fun orderHistory() = benchCase("OrderHistory (12 orders, 48 lines)", BenchmarkData.orderHistory()) { decoded ->
        assertEquals(8847291L, decoded.userId)
        assertEquals(12, decoded.orders.size)
        assertEquals(4, decoded.orders[0].lines.size)
        assertEquals("gold", decoded.loyaltyTier)
    }

    @Test
    fun mediaLibrary() = benchCase("MediaLibrary (24 assets, URLs, variants)", BenchmarkData.mediaLibrary()) { decoded ->
        assertEquals(65012L, decoded.albumId)
        assertEquals(24, decoded.assets.size)
        assertEquals(4, decoded.sharedWithUserIds.size)
        assertEquals("friends", decoded.permissions["visibility"])
    }

    @Test
    fun searchResultsPage() = benchCase("SearchResultsPage (25 hits, facets, snippets)", BenchmarkData.searchResultsPage()) { decoded ->
        assertEquals("kotlin flexbuffer performance", decoded.query)
        assertEquals(25, decoded.hits.size)
        assertEquals(5, decoded.facets.size)
        assertEquals("hybrid_v3", decoded.experimentFlags["ranker"])
    }

    @Test
    fun workoutSession() = benchCase("WorkoutSession (120 wearable samples)", BenchmarkData.workoutSession()) { decoded ->
        assertEquals("outdoor_run", decoded.sport)
        assertEquals(120, decoded.samples.size)
        assertEquals(5, decoded.zones.size)
        assertEquals(812, decoded.calories)
    }

    @Test
    fun bankingLedger() = benchCase("BankingLedger (80 transactions, statements, balances)", BenchmarkData.bankingLedger()) { decoded ->
        assertEquals(2300450091L, decoded.accountId)
        assertEquals(80, decoded.transactions.size)
        assertEquals("USD", decoded.currency)
        assertEquals(4, decoded.balances.size)
    }

    @Test
    fun rideHistory() = benchCase("RideHistory (36 trips, 648 route points)", BenchmarkData.rideHistory()) { decoded ->
        assertEquals(8847291L, decoded.riderId)
        assertEquals(36, decoded.trips.size)
        assertEquals(18, decoded.trips[0].routePoints.size)
        assertEquals("platinum", decoded.userTier)
    }

    @Test
    fun projectBoardSnapshot() = benchCase("ProjectBoardSnapshot (50 tasks, 200 comments)", BenchmarkData.projectBoardSnapshot()) { decoded ->
        assertEquals(120045L, decoded.boardId)
        assertEquals(5, decoded.columns.size)
        assertEquals(10, decoded.columns[0].tasks.size)
        assertEquals(4, decoded.columns[0].tasks[0].comments.size)
    }

    @Test
    fun documentCorpus() = benchCase("DocumentCorpus (16 docs, 80 revisions, 800 segments)", BenchmarkData.documentCorpus()) { decoded ->
        assertEquals(73001L, decoded.workspaceId)
        assertEquals(16, decoded.documents.size)
        assertEquals(5, decoded.documents[0].revisions.size)
        assertEquals(10, decoded.documents[0].revisions[0].segments.size)
    }

    @Test
    fun securityAuditTrail() = benchCase("SecurityAuditTrail (110 audit events)", BenchmarkData.securityAuditTrail()) { decoded ->
        assertEquals("bestbuds-prod", decoded.appId)
        assertEquals(110, decoded.events.size)
        assertEquals(4, decoded.summary.size)
        assertEquals("rbac-2026.05", decoded.policyVersion)
    }

    @Test
    fun graphSnapshot() = benchCase("GraphSnapshot (90 nodes, 150 edges)", BenchmarkData.graphSnapshot()) { decoded ->
        assertEquals(440012L, decoded.graphId)
        assertEquals(90, decoded.nodes.size)
        assertEquals(150, decoded.edges.size)
        assertEquals(3, decoded.properties.size)
    }

    @Test
    fun recommendationFeed() = benchCase("RecommendationFeed (60 ranked cards)", BenchmarkData.recommendationFeed()) { decoded ->
        assertEquals("feed_8847291_home_20260522", decoded.feedId)
        assertEquals(60, decoded.cards.size)
        assertEquals(3, decoded.cards[0].reasons.size)
        assertEquals("ranker-hybrid-v5", decoded.modelVersion)
    }

    @Test
    fun gameWorldState() = benchCase("GameWorldState (80 entities, inventory, quests)", BenchmarkData.gameWorldState()) { decoded ->
        assertEquals(9001L, decoded.worldId)
        assertEquals(80, decoded.entities.size)
        assertEquals(12, decoded.quests.size)
        assertEquals(5, decoded.entities[0].inventory.size)
    }

    @Test
    fun iotFleetSnapshot() = benchCase("IoTFleetSnapshot (64 devices, readings, alerts)", BenchmarkData.iotFleetSnapshot()) { decoded ->
        assertEquals("factory-floor-17", decoded.fleetId)
        assertEquals(64, decoded.devices.size)
        assertEquals(18, decoded.alerts.size)
        assertEquals(8, decoded.devices[0].readings.size)
    }

    @Test
    fun crmPortfolio() = benchCase("CRMPortfolio (28 accounts, contacts, opportunities)", BenchmarkData.crmPortfolio()) { decoded ->
        assertEquals(8847291L, decoded.ownerUserId)
        assertEquals(28, decoded.accounts.size)
        assertEquals(5, decoded.accounts[0].contacts.size)
        assertEquals(4, decoded.accounts[0].opportunities.size)
    }

    @Test
    fun travelItinerary() = benchCase("TravelItinerary (flights, hotels, activities)", BenchmarkData.travelItinerary()) { decoded ->
        assertEquals(77881234L, decoded.itineraryId)
        assertEquals(6, decoded.flights.size)
        assertEquals(5, decoded.hotels.size)
        assertEquals(24, decoded.activities.size)
    }

    @Test
    fun courseRoster() = benchCase("CourseRoster (72 students, modules, submissions)", BenchmarkData.courseRoster()) { decoded ->
        assertEquals(900120L, decoded.courseId)
        assertEquals(72, decoded.students.size)
        assertEquals(10, decoded.modules.size)
        assertEquals(8, decoded.students[0].submissions.size)
    }

    @Test
    fun shipmentBatch() = benchCase("ShipmentBatch (48 shipments, packages, events)", BenchmarkData.shipmentBatch()) { decoded ->
        assertEquals(500045L, decoded.batchId)
        assertEquals(48, decoded.shipments.size)
        assertEquals(3, decoded.shipments[0].packages.size)
        assertEquals(6, decoded.shipments[0].events.size)
    }

    @Test
    fun marketDataSnapshot() = benchCase("MarketDataSnapshot (32 instruments, order books, ticks)", BenchmarkData.marketDataSnapshot()) { decoded ->
        assertEquals("NASDAQ", decoded.market)
        assertEquals(32, decoded.instruments.size)
        assertEquals(20, decoded.instruments[0].orders.size)
        assertEquals(16, decoded.instruments[0].ticks.size)
    }

    @Test
    fun socialGraphDelta() = benchCase("SocialGraphDelta (users, edges, interactions)", BenchmarkData.socialGraphDelta()) { decoded ->
        assertEquals(44000012L, decoded.batchId)
        assertEquals(80, decoded.users.size)
        assertEquals(160, decoded.edges.size)
        assertEquals(140, decoded.interactions.size)
    }

    data class BenchRow(val name: String, val flexUs: Double, val serUs: Double, val jsonUs: Double, val flexB: Int, val jsonB: Int)

    private inline fun <reified T : Any> measure(name: String, data: T): BenchRow {
        registerGeneratedFlexCoders()
        repeat(warmup) { FlexBuffers.encode(data) }

        val flexBytes = FlexBuffers.encode(data)
        val flexEnc = measureTime { repeat(iterations) { FlexBuffers.encode(data) } }.inWholeMicroseconds.toDouble() / iterations
        val flexDec = measureTime { repeat(iterations) { FlexBuffers.decode<T>(flexBytes) } }.inWholeMicroseconds.toDouble() / iterations

        FlexCoderRegistry.clear()
        val serBytes = FlexBuffers.encode(serializer<T>(), data)
        repeat(warmup) { FlexBuffers.encode(serializer<T>(), data) }
        val serEnc = measureTime { repeat(iterations) { FlexBuffers.encode(serializer<T>(), data) } }.inWholeMicroseconds.toDouble() / iterations
        val serDec = measureTime { repeat(iterations) { FlexBuffers.decode(serializer<T>(), serBytes) } }.inWholeMicroseconds.toDouble() / iterations
        registerGeneratedFlexCoders()

        val jsonStr = json.encodeToString(serializer<T>(), data)
        repeat(warmup) { json.encodeToString(serializer<T>(), data) }
        val jsonEnc = measureTime { repeat(iterations) { json.encodeToString(serializer<T>(), data) } }.inWholeMicroseconds.toDouble() / iterations
        val jsonDec = measureTime { repeat(iterations) { json.decodeFromString(serializer<T>(), jsonStr) } }.inWholeMicroseconds.toDouble() / iterations

        return BenchRow(name, flexEnc + flexDec, serEnc + serDec, jsonEnc + jsonDec, flexBytes.size, jsonStr.length)
    }

    @Test
    fun summary() {
        println("\n╔══════════════════════════════════════════════════════════════╗")
        println("║            REALISTIC BENCHMARK SUMMARY                      ║")
        println("╚══════════════════════════════════════════════════════════════╝")

        val rows = listOf(
            measure("UserProfile", BenchmarkData.userProfile()),
            measure("ApiResponse", BenchmarkData.apiResponse()),
            measure("EventLog", BenchmarkData.eventLog()),
            measure("ChatThread", BenchmarkData.chatThread()),
            measure("ConfigSnapshot", BenchmarkData.configSnapshot()),
            measure("TimeSeries", BenchmarkData.timeSeriesChunk()),
            measure("NotificationInbox", BenchmarkData.notificationInbox()),
            measure("OrderHistory", BenchmarkData.orderHistory()),
            measure("MediaLibrary", BenchmarkData.mediaLibrary()),
            measure("SearchResults", BenchmarkData.searchResultsPage()),
            measure("WorkoutSession", BenchmarkData.workoutSession()),
            measure("BankingLedger", BenchmarkData.bankingLedger()),
            measure("RideHistory", BenchmarkData.rideHistory()),
            measure("ProjectBoard", BenchmarkData.projectBoardSnapshot()),
            measure("DocumentCorpus", BenchmarkData.documentCorpus()),
            measure("SecurityAudit", BenchmarkData.securityAuditTrail()),
            measure("GraphSnapshot", BenchmarkData.graphSnapshot()),
            measure("Recommendation", BenchmarkData.recommendationFeed()),
            measure("GameWorld", BenchmarkData.gameWorldState()),
            measure("IoTFleet", BenchmarkData.iotFleetSnapshot()),
            measure("CRMPortfolio", BenchmarkData.crmPortfolio()),
            measure("TravelItinerary", BenchmarkData.travelItinerary()),
            measure("CourseRoster", BenchmarkData.courseRoster()),
            measure("ShipmentBatch", BenchmarkData.shipmentBatch()),
            measure("MarketData", BenchmarkData.marketDataSnapshot()),
            measure("SocialGraphDelta", BenchmarkData.socialGraphDelta())
        )

        println()
        println("  %-20s %10s %10s %10s %8s %8s %8s".format(
            "Case", "FlexCoder", "Serialz", "JSON", "Flex B", "JSON B", "Speedup"))
        println("  " + "─".repeat(86))
        for (r in rows) {
            val speedup = if (r.flexUs > 0) r.jsonUs / r.flexUs else 0.0
            println("  %-20s %8.0f us %8.0f us %8.0f us %7dB %7dB %6.1fx".format(
                r.name, r.flexUs, r.serUs, r.jsonUs, r.flexB, r.jsonB, speedup))
        }
        println()
    }
}
