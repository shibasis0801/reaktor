@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.core.registerGeneratedFlexCoders
import dev.shibasis.reaktor.flexbuffer.core.FlexBufferPool
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import dev.shibasis.reaktor.flexbuffer.core.FlexCoder
import dev.shibasis.reaktor.flexbuffer.core.FlexCoderRegistry
import dev.shibasis.reaktor.flexbuffer.core.toByteArray
import dev.shibasis.reaktor.flexbuffer.core.toFlexMap
import dev.shibasis.reaktor.flexbuffer.flatbuffers.ArrayReadBuffer
import dev.shibasis.reaktor.flexbuffer.flatbuffers.FlexRead
import dev.shibasis.reaktor.flexbuffer.flatbuffers.FlexBuffersBuilder
import dev.shibasis.reaktor.flexbuffer.flatbuffers.Reference
import dev.shibasis.reaktor.flexbuffer.flatbuffers.getRoot
import dev.shibasis.reaktor.flexbuffer.flatbuffers.Map as FlexMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

class AdversarialPerformanceHarnessTest {
    @Serializable
    private data class TinyStatus(
        val createdAt: Long,
        val id: Long,
        val score: Double,
        val status: String,
        val username: String,
        val verified: Boolean
    )

    @Serializable
    private data class StringRow(
        val body: String,
        val id: Int,
        val tag: String,
        val title: String
    )

    @Serializable
    private data class StringTable(
        val rows: List<StringRow>
    )

    private object TinyStatusFlexCoder : FlexCoder<TinyStatus> {
        override fun encode(builder: FlexBuffersBuilder, value: TinyStatus, key: String?) {
            val start = builder.startMap()
            builder.set("createdAt", value.createdAt)
            builder.set("id", value.id)
            builder.set("score", value.score)
            builder.set("status", value.status)
            builder.set("username", value.username)
            builder.set("verified", value.verified)
            builder.endMap(start, key, presorted = true)
        }

        override fun decode(ref: Reference): TinyStatus {
            // Positional decode, mirroring KSP-generated decodeAt: zero Map/Vector
            // allocations, hoisted (buf, end, bw, tb) locals.
            val m = ref.toMap()
            val buf = m.buf; val end = m.end; val bw = m.byteWidth
            val tb = end + FlexRead.size(buf, end, bw) * bw
            return TinyStatus(
                createdAt = FlexRead.getLong(buf, end, bw, tb, 0),
                id = FlexRead.getLong(buf, end, bw, tb, 1),
                score = FlexRead.getDouble(buf, end, bw, tb, 2),
                status = FlexRead.getString(buf, end, bw, tb, 3),
                username = FlexRead.getString(buf, end, bw, tb, 4),
                verified = FlexRead.getBoolean(buf, end, bw, 5)
            )
        }
    }

    private object StringTableFlexCoder : FlexCoder<StringTable> {
        override fun encode(builder: FlexBuffersBuilder, value: StringTable, key: String?) {
            val start = builder.startMap()
            val rows = builder.startVector()
            value.rows.forEach { row ->
                val rowStart = builder.startMap()
                builder.set("body", row.body)
                builder.set("id", row.id)
                builder.set("tag", row.tag)
                builder.set("title", row.title)
                builder.endMap(rowStart, presorted = true)
            }
            builder.endVector("rows", rows)
            builder.endMap(start, key, presorted = true)
        }

        override fun decode(ref: Reference): StringTable {
            val m = ref.toMap()
            val buf = m.buf; val end = m.end; val bw = m.byteWidth
            val tb = end + FlexRead.size(buf, end, bw) * bw
            val ve = FlexRead.childEnd(buf, end, bw, 0)
            val vw = FlexRead.childWidth(buf, tb, 0)
            val n = FlexRead.size(buf, ve, vw)
            val vtb = ve + n * vw
            return StringTable(
                rows = List(n) { j ->
                    val re = FlexRead.childEnd(buf, ve, vw, j)
                    val rw = FlexRead.childWidth(buf, vtb, j)
                    val rtb = re + FlexRead.size(buf, re, rw) * rw
                    StringRow(
                        body = FlexRead.getString(buf, re, rw, rtb, 0),
                        id = FlexRead.getInt(buf, re, rw, rtb, 1),
                        tag = FlexRead.getString(buf, re, rw, rtb, 2),
                        title = FlexRead.getString(buf, re, rw, rtb, 3)
                    )
                }
            )
        }
    }

    @JvmInline
    private value class TinyStatusAccessor(private val map: FlexMap) {
        val createdAt: Long get() = map.getLong(0)
        val id: Long get() = map.getLong(1)
        val score: Double get() = map.getDouble(2)
        val status: String get() = map.getString(3)
        val username: String get() = map.getString(4)
        val verified: Boolean get() = map.getBoolean(5)

        fun readAll(): Long {
            return createdAt + id + score.toLong() + status.length + username.length + if (verified) 1 else 0
        }
    }

    private val json = Json { encodeDefaults = true }
    private val warmup = 750
    private val iterations = 7_500
    private val runs = 5
    private var sink: Any? = null

    private data class Stat(
        val label: String,
        val medianUs: Double,
        val minUs: Double,
        val maxUs: Double,
        val meanUs: Double,
        val stddev: Double
    )

    private data class JsonFlexRow(
        val case: String,
        val flexCoderUs: Double?,
        val serializerUs: Double?,
        val accessorUs: Double?,
        val jsonUs: Double?,
        val jsonScanUs: Double?,
        val flexBytes: Int,
        val jsonBytes: Int,
        val note: String
    )

    private data class CppMetricRow(
        val case: String,
        val kotlinUs: Double,
        val cppUs: Double,
        val kotlinBytes: Int,
        val cppBytes: Int?,
        val note: String
    )

    private inline fun bench(label: String, warmupIters: Int = warmup, measuredIters: Int = iterations, crossinline block: () -> Any?): Stat {
        repeat(warmupIters) { sink = block() }
        val samples = DoubleArray(runs)
        repeat(runs) { run ->
            val elapsed = measureTime {
                repeat(measuredIters) {
                    sink = block()
                }
            }
            samples[run] = elapsed.inWholeMicroseconds.toDouble() / measuredIters
        }
        samples.sort()
        val mean = samples.average()
        val sd = sqrt(samples.sumOf { (it - mean) * (it - mean) } / samples.size)
        val stat = Stat(label, samples[samples.size / 2], samples.first(), samples.last(), mean, sd)
        println("  %-45s %8.3f us/op  (min=%.3f max=%.3f sd=%.3f)".format(
            label, stat.medianUs, stat.minUs, stat.maxUs, stat.stddev
        ))
        return stat
    }

    private fun registerHarnessCoders() {
        registerAllCoders()
        registerGeneratedFlexCoders()
        FlexCoderRegistry.register(TinyStatus::class, TinyStatusFlexCoder)
        FlexCoderRegistry.registerBySerialName(TinyStatus.serializer().descriptor.serialName, TinyStatusFlexCoder)
        FlexCoderRegistry.register(StringTable::class, StringTableFlexCoder)
        FlexCoderRegistry.registerBySerialName(StringTable.serializer().descriptor.serialName, StringTableFlexCoder)
    }

    private inline fun <reified T : Any> measureJsonVsFlex(
        name: String,
        data: T,
        accessorLabel: String,
        noinline accessor: (ByteArray) -> Any?,
        noinline jsonScan: ((String) -> Any?)? = null,
        note: String,
        measuredIters: Int = iterations
    ): JsonFlexRow {
        println("\n-- $name --")
        val typeSerializer = serializer<T>()

        registerHarnessCoders()
        val flexCoderEnc = bench("FlexCoder encode", measuredIters = measuredIters) { FlexBuffers.encode(data) }
        val flexBytes = FlexBuffers.encode(data)
        val flexCoderDec = bench("FlexCoder decode", measuredIters = measuredIters) { FlexBuffers.decode<T>(flexBytes) }
        val accessorRead = bench(accessorLabel, measuredIters = measuredIters) { accessor(flexBytes) }

        FlexCoderRegistry.clear()
        val serializerEnc = bench("kotlinx Flex encode", measuredIters = measuredIters) { FlexBuffers.encode(typeSerializer, data) }
        val serializerBytes = FlexBuffers.encode(typeSerializer, data)
        val serializerDec = bench("kotlinx Flex decode", measuredIters = measuredIters) { FlexBuffers.decode(typeSerializer, serializerBytes) }

        val jsonEnc = bench("kotlinx JSON encode", measuredIters = measuredIters) { json.encodeToString(typeSerializer, data) }
        val jsonString = json.encodeToString(typeSerializer, data)
        val jsonDec = bench("kotlinx JSON decode", measuredIters = measuredIters) { json.decodeFromString(typeSerializer, jsonString) }
        val jsonScanStat = jsonScan?.let { scan ->
            bench("controlled JSON token scan", measuredIters = measuredIters) { scan(jsonString) }
        }

        registerHarnessCoders()
        assertEquals(data, FlexBuffers.decode<T>(flexBytes))
        FlexCoderRegistry.clear()

        return JsonFlexRow(
            case = name,
            flexCoderUs = flexCoderEnc.medianUs + flexCoderDec.medianUs,
            serializerUs = serializerEnc.medianUs + serializerDec.medianUs,
            accessorUs = accessorRead.medianUs,
            jsonUs = jsonEnc.medianUs + jsonDec.medianUs,
            jsonScanUs = jsonScanStat?.medianUs,
            flexBytes = flexBytes.size,
            jsonBytes = jsonString.encodeToByteArray().size,
            note = note
        )
    }

    @Test
    fun jsonVsFlexKotlinAdversarialHarness() {
        println("\n" + "=".repeat(104))
        println("KOTLIN ADVERSARIAL HARNESS 1: JSON vs FLEX")
        println("Warmup=$warmup iterations=$iterations runs=$runs")
        println("Includes generated FlexCoders, manual FlexCoders, raw kotlinx Flex serialization, accessors, and JSON.")
        println("=".repeat(104))

        val tiny = TinyStatus(
            createdAt = 1_716_307_200_000L,
            id = 8_847_291L,
            score = 98.25,
            status = "active",
            username = "shibasis.patnaik",
            verified = true
        )

        val rows = mutableListOf<JsonFlexRow>()
        rows += measureJsonVsFlex(
            name = "TinyStatus",
            data = tiny,
            accessorLabel = "TinyStatus accessor read all",
            accessor = { bytes -> TinyStatusAccessor(bytes.toFlexMap()).readAll() },
            jsonScan = { scanJsonTokens(it, """"createdAt":""", """"username":"""", """"verified":true""") },
            note = "Tiny string-heavy object; JSON should be size-competitive."
        )

        rows += measureJsonVsFlex(
            name = "FlatPrimitives",
            data = FlexBufferBenchmark.FlatPrimitives(),
            accessorLabel = "Flat primitive direct reads",
            accessor = { bytes -> scanFlatPrimitivesAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"b":true""", """"s":"benchmark""", """"sh":32000""") },
            note = "Nine scalar fields; self-describing key overhead should be exposed."
        )

        rows += measureJsonVsFlex(
            name = "UserProfile",
            data = BenchmarkData.userProfile(),
            accessorLabel = "Generated accessor read all",
            accessor = { bytes ->
                val a = BenchUserProfileAccessor(bytes.toFlexMap())
                a.id + a.username.length + a.displayName.length + a.email.length + a.bio.length +
                    a.avatarUrl.length + a.followerCount + a.followingCount + a.postCount +
                    a.createdAtEpochMs + a.tags.size + a.settings.size + a.address.city.length +
                    if (a.verified) 1 else 0
            },
            jsonScan = { scanJsonTokens(it, """"username":"""", """"verified":true""", """"city":"Bengaluru"""") },
            note = "Generated KSP accessor and FlexCoder on a realistic string-heavy profile."
        )

        rows += measureJsonVsFlex(
            name = "EventLog",
            data = BenchmarkData.eventLog(),
            accessorLabel = "Raw event keyed accessor",
            accessor = { bytes -> scanEventLogAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"eventId":"""", """"properties":""", """"success":true""") },
            note = "Telemetry payload with small maps; JSON frequently competes here."
        )

        rows += measureJsonVsFlex(
            name = "ConfigSnapshot",
            data = BenchmarkData.configSnapshot(),
            accessorLabel = "Raw config keyed accessor",
            accessor = { bytes -> scanConfigSnapshotAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"version":47""", """"features":""", """"debugMode":false""") },
            note = "Nested maps and many small strings; probes binary map/key overhead."
        )

        rows += measureJsonVsFlex(
            name = "ChatThread",
            data = BenchmarkData.chatThread(),
            accessorLabel = "Raw chat keyed accessor",
            accessor = { bytes -> scanChatThreadAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"threadId":55012""", """"messages":[""", """"pinned":true""") },
            note = "Variable text and reaction maps; realistic app payload."
        )

        rows += measureJsonVsFlex(
            name = "ApiResponse",
            data = BenchmarkData.apiResponse(),
            accessorLabel = "Raw API keyed accessor",
            accessor = { bytes -> scanApiResponseAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"status":200""", """"items":[""", """"metadata":""") },
            note = "Twenty nested products; JSON encode can be strong on object arrays."
        )

        rows += measureJsonVsFlex(
            name = "CollectionHeavy",
            data = FlexBufferBenchmark.CollectionHeavy(),
            accessorLabel = "Raw collection keyed accessor",
            accessor = { bytes -> scanCollectionHeavyAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"intList":[""", """"nestedList":[""", """"stringMap":""") },
            note = "Lists, nested lists, and maps; deliberately allocation-heavy."
        )

        rows += measureJsonVsFlex(
            name = "NotificationInbox",
            data = BenchmarkData.notificationInbox(),
            accessorLabel = "Raw notification feed scan",
            accessor = { bytes -> scanNotificationInboxAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"unreadCount":9""", """"items":[""", """"channelSettings":""") },
            note = "Mobile notification inbox with nested action and metadata rows."
        )

        rows += measureJsonVsFlex(
            name = "OrderHistory",
            data = BenchmarkData.orderHistory(),
            accessorLabel = "Raw order history scan",
            accessor = { bytes -> scanOrderHistoryAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"orders":[""", """"summaryCents":""", """"loyaltyTier":"gold"""") },
            note = "Commerce order history with repeated order lines and money fields."
        )

        rows += measureJsonVsFlex(
            name = "MediaLibrary",
            data = BenchmarkData.mediaLibrary(),
            accessorLabel = "Raw media manifest scan",
            accessor = { bytes -> scanMediaLibraryAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"assets":[""", """"variants":""", """"permissions":""") },
            note = "URL-heavy media manifest with assets, labels, variants, and sharing."
        )

        rows += measureJsonVsFlex(
            name = "SearchResults",
            data = BenchmarkData.searchResultsPage(),
            accessorLabel = "Raw search results scan",
            accessor = { bytes -> scanSearchResultsAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"query":"""", """"hits":[""", """"facets":[""") },
            note = "Search response with snippets, facets, scores, and attributes."
        )

        rows += measureJsonVsFlex(
            name = "WorkoutSession",
            data = BenchmarkData.workoutSession(),
            accessorLabel = "Raw workout samples scan",
            accessor = { bytes -> scanWorkoutSessionAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"samples":[""", """"zones":""", """"sport":"outdoor_run"""") },
            note = "Wearable activity sync with a medium numeric sample stream."
        )

        rows += measureJsonVsFlex(
            name = "StringTable",
            data = stringTable(rows = 200, bodyLen = 80),
            accessorLabel = "Raw Flex row accessor scan",
            accessor = { bytes -> scanStringTableAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"rows":[""", """"body":"""", """"title":"title_199"""") },
            note = "String-heavy table built to challenge binary self-description overhead."
        )

        rows += measureJsonVsFlex(
            name = "TimeSeries",
            data = BenchmarkData.timeSeriesChunk(),
            accessorLabel = "Raw Flex numeric accessor scan",
            accessor = { bytes -> scanTimeSeriesAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"seriesId":"""", """"values":[""", """"timestamps":[""") },
            note = "Numeric/vector-heavy case where FlexBuffers should have the strongest chance."
        )

        addExpandedJsonFlexRows(rows)

        println("\nJSON vs FLEX LOSS LEDGER")
        println("  %-18s %12s %12s %12s %12s %12s %9s %9s %9s  %s".format(
            "Case", "FlexCoder", "kotlinxFlex", "Accessor", "kxJSON", "JSONScan", "Flex B", "JSON B", "Winner", "Note"
        ))
        println("  " + "-".repeat(162))
        rows.forEach { row ->
            val bestFlex = listOfNotNull(row.flexCoderUs, row.serializerUs, row.accessorUs).minOrNull() ?: Double.POSITIVE_INFINITY
            val bestJson = listOfNotNull(row.jsonUs, row.jsonScanUs).minOrNull() ?: Double.POSITIVE_INFINITY
            val winner = if (bestJson < bestFlex) "JSON" else "Flex"
            println("  %-18s %9.2f us %9.2f us %9.2f us %9.2f us %9.2f us %8dB %8dB %9s  %s".format(
                row.case,
                row.flexCoderUs ?: -1.0,
                row.serializerUs ?: -1.0,
                row.accessorUs ?: -1.0,
                row.jsonUs ?: -1.0,
                row.jsonScanUs ?: -1.0,
                row.flexBytes,
                row.jsonBytes,
                winner,
                row.note
            ))
        }
        val fullRoundTripLosses = rows.count { (it.jsonUs ?: Double.POSITIVE_INFINITY) < (it.flexCoderUs ?: Double.POSITIVE_INFINITY) }
        val bestPathLosses = rows.count {
            val bestFlex = listOfNotNull(it.flexCoderUs, it.serializerUs, it.accessorUs).minOrNull() ?: Double.POSITIVE_INFINITY
            val bestJson = listOfNotNull(it.jsonUs, it.jsonScanUs).minOrNull() ?: Double.POSITIVE_INFINITY
            bestJson < bestFlex
        }
        val sizeLosses = rows.count { it.jsonBytes < it.flexBytes }
        println("\nFlexCoder lost $fullRoundTripLosses/${rows.size} full roundtrip time comparisons to kotlinx JSON.")
        println("Mixed best-path Flex lost $bestPathLosses/${rows.size} time comparisons to the best JSON path (including controlled token scans).")
        println("Flex lost $sizeLosses/${rows.size} size comparisons against JSON in this Kotlin adversarial set.")
        printSeparatedJsonFlexLedgers(rows)
        println("sinkType=${sink?.let { it::class.simpleName } ?: "null"}")
    }

    private fun printSeparatedJsonFlexLedgers(rows: List<JsonFlexRow>) {
        println("\nFULL ROUND-TRIP LEDGER")
        println("  %-18s %12s %12s %12s %9s  %s".format(
            "Case", "FlexCoder", "kotlinxFlex", "kxJSON", "Winner", "Note"
        ))
        println("  " + "-".repeat(116))
        rows.forEach { row ->
            val bestFlex = listOfNotNull(row.flexCoderUs, row.serializerUs).minOrNull() ?: Double.POSITIVE_INFINITY
            val json = row.jsonUs ?: Double.POSITIVE_INFINITY
            val winner = if (json < bestFlex) "JSON" else "Flex"
            println("  %-18s %9.2f us %9.2f us %9.2f us %9s  %s".format(
                row.case,
                row.flexCoderUs ?: -1.0,
                row.serializerUs ?: -1.0,
                row.jsonUs ?: -1.0,
                winner,
                row.note
            ))
        }
        val fullLosses = rows.count {
            (it.jsonUs ?: Double.POSITIVE_INFINITY) < (listOfNotNull(it.flexCoderUs, it.serializerUs).minOrNull() ?: Double.POSITIVE_INFINITY)
        }
        println("Full round-trip Flex lost $fullLosses/${rows.size} comparisons against kotlinx JSON.")

        println("\nACCESSOR VS TOKEN-SCAN LEDGER")
        println("  %-18s %12s %12s %9s  %s".format("Case", "FlexAccessor", "JSONScan", "Winner", "Note"))
        println("  " + "-".repeat(102))
        rows.forEach { row ->
            val flex = row.accessorUs ?: Double.POSITIVE_INFINITY
            val json = row.jsonScanUs ?: Double.POSITIVE_INFINITY
            val winner = when {
                row.jsonScanUs == null -> "Flex"
                json < flex -> "JSON"
                else -> "Flex"
            }
            println("  %-18s %9.2f us %9.2f us %9s  %s".format(
                row.case,
                row.accessorUs ?: -1.0,
                row.jsonScanUs ?: -1.0,
                winner,
                row.note
            ))
        }
        val tokenScanLosses = rows.count {
            it.jsonScanUs != null && it.jsonScanUs < (it.accessorUs ?: Double.POSITIVE_INFINITY)
        }
        println("Accessor-only Flex lost $tokenScanLosses/${rows.size} comparisons against controlled JSON token scans.")

        println("\nWIRE SIZE LEDGER")
        println("  %-18s %10s %10s %9s  %s".format("Case", "FlexBytes", "JSONBytes", "Winner", "Note"))
        println("  " + "-".repeat(98))
        rows.forEach { row ->
            val winner = if (row.jsonBytes < row.flexBytes) "JSON" else "Flex"
            println("  %-18s %10d %10d %9s  %s".format(
                row.case,
                row.flexBytes,
                row.jsonBytes,
                winner,
                row.note
            ))
        }
        val sizeLosses = rows.count { it.jsonBytes < it.flexBytes }
        println("Wire-size Flex lost $sizeLosses/${rows.size} comparisons against JSON.")
    }

    private fun addExpandedJsonFlexRows(rows: MutableList<JsonFlexRow>) {
        rows += measureJsonVsFlex(
            name = "BankingLedger",
            data = BenchmarkData.bankingLedger(),
            accessorLabel = "Raw banking ledger scan",
            accessor = { bytes -> scanBankingLedgerAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"transactions":[""", """"balances":""", """"statements":""") },
            note = "Fintech account ledger with 80 transactions, statements, balances, and tags.",
            measuredIters = 1_500
        )
        rows += measureJsonVsFlex(
            name = "RideHistory",
            data = BenchmarkData.rideHistory(),
            accessorLabel = "Raw rideshare route scan",
            accessor = { bytes -> scanRideHistoryAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"trips":[""", """"routePoints":[""", """"driverRatings":""") },
            note = "Rideshare archive with 36 trips and 648 route points.",
            measuredIters = 1_500
        )
        rows += measureJsonVsFlex(
            name = "ProjectBoard",
            data = BenchmarkData.projectBoardSnapshot(),
            accessorLabel = "Raw project board scan",
            accessor = { bytes -> scanProjectBoardAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"columns":[""", """"comments":[""", """"metadata":""") },
            note = "Project board snapshot with 50 tasks, comments, labels, and member metadata.",
            measuredIters = 1_200
        )
        rows += measureJsonVsFlex(
            name = "DocumentCorpus",
            data = BenchmarkData.documentCorpus(),
            accessorLabel = "Raw document corpus scan",
            accessor = { bytes -> scanDocumentCorpusAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"documents":[""", """"revisions":[""", """"segments":[""") },
            note = "Workspace document corpus with 80 revisions and 800 text segments.",
            measuredIters = 900
        )
        rows += measureJsonVsFlex(
            name = "SecurityAudit",
            data = BenchmarkData.securityAuditTrail(),
            accessorLabel = "Raw security audit scan",
            accessor = { bytes -> scanSecurityAuditAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"events":[""", """"summary":""", """"policyVersion":"""") },
            note = "Security audit export with 110 events and metadata maps.",
            measuredIters = 1_200
        )
        rows += measureJsonVsFlex(
            name = "GraphSnapshot",
            data = BenchmarkData.graphSnapshot(),
            accessorLabel = "Raw graph snapshot scan",
            accessor = { bytes -> scanGraphSnapshotAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"nodes":[""", """"edges":[""", """"properties":""") },
            note = "Graph/flow snapshot with 90 nodes and 150 edges.",
            measuredIters = 1_200
        )
        rows += measureJsonVsFlex(
            name = "Recommendation",
            data = BenchmarkData.recommendationFeed(),
            accessorLabel = "Raw recommendation feed scan",
            accessor = { bytes -> scanRecommendationFeedAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"cards":[""", """"reasons":[""", """"modelVersion":"""") },
            note = "Ranked recommendation feed with scores, explanations, metadata, and actions.",
            measuredIters = 1_500
        )
        rows += measureJsonVsFlex(
            name = "GameWorld",
            data = BenchmarkData.gameWorldState(),
            accessorLabel = "Raw game world scan",
            accessor = { bytes -> scanGameWorldAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"entities":[""", """"inventory":[""", """"quests":[""") },
            note = "Game/world state with 80 entities, inventory, attributes, and quests.",
            measuredIters = 1_200
        )
        addExpandedJsonFlexRowsTail(rows)
    }

    private fun addExpandedJsonFlexRowsTail(rows: MutableList<JsonFlexRow>) {
        rows += measureJsonVsFlex(
            name = "IoTFleet",
            data = BenchmarkData.iotFleetSnapshot(),
            accessorLabel = "Raw IoT fleet scan",
            accessor = { bytes -> scanIoTFleetAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"devices":[""", """"readings":[""", """"alerts":[""") },
            note = "Device fleet telemetry with 64 devices, readings, metrics, and alerts.",
            measuredIters = 1_000
        )
        rows += measureJsonVsFlex(
            name = "CRMPortfolio",
            data = BenchmarkData.crmPortfolio(),
            accessorLabel = "Raw CRM portfolio scan",
            accessor = { bytes -> scanCRMPortfolioAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"accounts":[""", """"opportunities":[""", """"contacts":[""") },
            note = "Sales account portfolio with contacts, opportunities, tags, and pipeline state.",
            measuredIters = 1_200
        )
        rows += measureJsonVsFlex(
            name = "TravelItinerary",
            data = BenchmarkData.travelItinerary(),
            accessorLabel = "Raw travel itinerary scan",
            accessor = { bytes -> scanTravelItineraryAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"flights":[""", """"hotels":[""", """"activities":[""") },
            note = "Travel itinerary mixing flights, hotel bookings, activities, and segments.",
            measuredIters = 1_500
        )
        rows += measureJsonVsFlex(
            name = "CourseRoster",
            data = BenchmarkData.courseRoster(),
            accessorLabel = "Raw course roster scan",
            accessor = { bytes -> scanCourseRosterAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"students":[""", """"submissions":[""", """"modules":[""") },
            note = "Education roster with 72 students, modules, grades, and submissions.",
            measuredIters = 900
        )
        rows += measureJsonVsFlex(
            name = "ShipmentBatch",
            data = BenchmarkData.shipmentBatch(),
            accessorLabel = "Raw shipment batch scan",
            accessor = { bytes -> scanShipmentBatchAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"shipments":[""", """"packages":[""", """"events":[""") },
            note = "Logistics shipment batch with package dimensions and tracking events.",
            measuredIters = 1_200
        )
        rows += measureJsonVsFlex(
            name = "MarketData",
            data = BenchmarkData.marketDataSnapshot(),
            accessorLabel = "Raw market data scan",
            accessor = { bytes -> scanMarketDataAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"instruments":[""", """"orders":[""", """"ticks":[""") },
            note = "Market data snapshot with 32 instruments, order books, and quote ticks.",
            measuredIters = 900
        )
        rows += measureJsonVsFlex(
            name = "SocialGraphDelta",
            data = BenchmarkData.socialGraphDelta(),
            accessorLabel = "Raw social graph delta scan",
            accessor = { bytes -> scanSocialGraphDeltaAccessor(bytes) },
            jsonScan = { scanJsonTokens(it, """"users":[""", """"edges":[""", """"interactions":[""") },
            note = "Social graph sync delta with users, edges, interactions, and metadata.",
            measuredIters = 1_000
        )
    }

    @Test
    fun flexKotlinVsFlexCppAdversarialHarness() {
        println("\n" + "=".repeat(104))
        println("KOTLIN ADVERSARIAL HARNESS 2: FLEX-KOTLIN vs FLEX-CPP")
        println("Warmup=$warmup iterations=$iterations runs=$runs")
        println("Runs the C++ adversarial harness, then measures equivalent Kotlin Flex paths.")
        println("=".repeat(104))

        val cpp = runCppAdversarialHarness()

        registerHarnessCoders()
        val tinyFlex = FlexBuffers.encode(TinyStatus(1_716_307_200_000L, 8_847_291L, 98.25, "active", "shibasis.patnaik", true))
        val stringTableFlex = FlexBuffers.encode(stringTable(rows = 200, bodyLen = 80))
        val timeSeriesFlex = FlexBuffers.encode(BenchmarkData.timeSeriesChunk())
        FlexCoderRegistry.clear()

        val sparseFlex = encodeSparseOptionsFlex(present = 10)
        val sparseMissingKeys = List(256) { i -> "optional_%04d".format(i) }
        val wideFlex = encodeMapNKeys(1_024)
        val wideKeys = wideRandomKeys(1_024, 64)

        val uniqueIters = 1_500
        val rows = mutableListOf<CppMetricRow>()

        rows += CppMetricRow(
            case = "TinyStatus key decode",
            kotlinUs = bench("Kotlin TinyStatus key decode") { decodeTinyStatusKey(tinyFlex) }.medianUs,
            cppUs = cpp.requireMetric("FlexBuffer full key decode"),
            kotlinBytes = tinyFlex.size,
            cppBytes = null,
            note = "Kotlin Reference/key lookup vs C++ key lookup."
        )

        rows += CppMetricRow(
            case = "TinyStatus index decode",
            kotlinUs = bench("Kotlin TinyStatus index decode") { TinyStatusFlexCoder.decode(FlexBuffers.getRoot(tinyFlex)) }.medianUs,
            cppUs = cpp.requireMetric("FlexBuffer full index decode"),
            kotlinBytes = tinyFlex.size,
            cppBytes = null,
            note = "Manual Kotlin FlexCoder vs C++ index decode."
        )

        rows += CppMetricRow(
            case = "TinyStatus partial key",
            kotlinUs = bench("Kotlin TinyStatus 3-field key read") { decodeTinyStatusPartialKey(tinyFlex) }.medianUs,
            cppUs = cpp.requireMetric("FlexBuffer 3-field read"),
            kotlinBytes = tinyFlex.size,
            cppBytes = null,
            note = "Partial keyed access, where object materialization is not required."
        )

        rows += CppMetricRow(
            case = "Sparse missing lookups",
            kotlinUs = bench("Kotlin 256 missing optional lookups") { decodeSparseMissing(sparseFlex, sparseMissingKeys) }.medianUs,
            cppUs = cpp.requireMetric("FlexBuffer 256 missing optional lookups"),
            kotlinBytes = sparseFlex.size,
            cppBytes = null,
            note = "Many absent keys; probes the map lookup miss path with keys precomputed outside the measured loop."
        )

        rows += CppMetricRow(
            case = "StringTable scan",
            kotlinUs = bench("Kotlin StringTable accessor scan", measuredIters = 2_000) { scanStringTableAccessor(stringTableFlex) }.medianUs,
            cppUs = cpp.requireMetric("FlexBuffer scan 200 rows"),
            kotlinBytes = stringTableFlex.size,
            cppBytes = null,
            note = "String-heavy row scan."
        )

        rows += CppMetricRow(
            case = "TimeSeries index scan",
            kotlinUs = bench("Kotlin TimeSeries numeric accessor scan") { scanTimeSeriesAccessor(timeSeriesFlex) }.medianUs,
            cppUs = cpp.requireMetric("FlexBuffer indexed full decode"),
            kotlinBytes = timeSeriesFlex.size,
            cppBytes = 4_852,
            note = "Numeric/vector-heavy scan; C++ has fixed benchmark shape."
        )

        rows += CppMetricRow(
            case = "Wide random key reads",
            kotlinUs = bench("Kotlin 64 random key reads") { decodeWideRandomKeys(wideFlex, wideKeys) }.medianUs,
            cppUs = cpp.requireMetric("FlexBuffer 64 random key reads"),
            kotlinBytes = wideFlex.size,
            cppBytes = 16_396,
            note = "Wide map random keyed access."
        )

        rows += CppMetricRow(
            case = "Wide sequential index",
            kotlinUs = bench("Kotlin 64 sequential index reads") { decodeWideSequentialIndexes(wideFlex, 64) }.medianUs,
            cppUs = cpp.requireMetric("FlexBuffer 64 sequential index reads"),
            kotlinBytes = wideFlex.size,
            cppBytes = 16_396,
            note = "Narrow indexed Flex path compared with the C++ indexed path."
        )

        rows += CppMetricRow(
            case = "Unique strings encode share",
            kotlinUs = bench("Kotlin encode SHARE_KEYS_AND_STRINGS", measuredIters = uniqueIters) {
                encodeUniqueStringPayload(share = true, rows = 200, len = 64).size
            }.medianUs,
            cppUs = cpp.requireMetric("Flex encode SHARE_KEYS_AND_STRINGS"),
            kotlinBytes = encodeUniqueStringPayload(share = true, rows = 200, len = 64).size,
            cppBytes = 33_852,
            note = "Kotlin builder sharing overhead vs C++ builder sharing overhead."
        )

        rows += CppMetricRow(
            case = "Unique strings no share",
            kotlinUs = bench("Kotlin encode SHARE_NONE", measuredIters = uniqueIters) {
                encodeUniqueStringPayload(share = false, rows = 200, len = 64).size
            }.medianUs,
            cppUs = cpp.requireMetric("Flex encode no sharing"),
            kotlinBytes = encodeUniqueStringPayload(share = false, rows = 200, len = 64).size,
            cppBytes = 34_020,
            note = "Builder baseline with sharing disabled on unique strings."
        )

        println("\nFLEX-KOTLIN vs FLEX-CPP LEDGER")
        println("  %-27s %12s %12s %10s %10s %9s  %s".format(
            "Case", "Kotlin", "C++", "Kt/C++", "Kt B", "Winner", "Note"
        ))
        println("  " + "-".repeat(132))
        rows.forEach { row ->
            val ratio = row.kotlinUs / row.cppUs
            val winner = if (row.kotlinUs <= row.cppUs) "Kotlin" else "C++"
            val sizeText = row.cppBytes?.let { "${row.kotlinBytes}/$it" } ?: row.kotlinBytes.toString()
            println("  %-27s %9.2f us %9.2f us %9.1fx %10s %9s  %s".format(
                row.case,
                row.kotlinUs,
                row.cppUs,
                ratio,
                sizeText,
                winner,
                row.note
            ))
        }
        val losses = rows.count { it.kotlinUs > it.cppUs }
        println("\nKotlin Flex lost $losses/${rows.size} time comparisons against C++ Flex in this adversarial set.")
        assertTrue(rows.isNotEmpty())
        println("sinkType=${sink?.let { it::class.simpleName } ?: "null"}")
    }

    private fun stringTable(rows: Int, bodyLen: Int): StringTable {
        return StringTable(
            rows = List(rows) { index ->
                StringRow(
                    body = adversarialText(index, bodyLen),
                    id = index,
                    tag = if (index % 2 == 0) "even" else "odd",
                    title = "title_$index"
                )
            }
        )
    }

    private fun adversarialText(row: Int, len: Int): String {
        return buildString(len) {
            repeat(len) { i -> append(('a'.code + ((row + i) % 26)).toChar()) }
        }
    }

    private fun scanJsonTokens(json: String, vararg tokens: String): Int {
        var sum = 0
        tokens.forEach { token ->
            val index = json.indexOf(token)
            if (index >= 0) sum += index + token.length
        }
        return sum
    }

    private fun scanStringTableAccessor(bytes: ByteArray): Long {
        val rows = bytes.toFlexMap().getVector(0)
        var sum = 0L
        for (i in 0 until rows.size) {
            val row = rows.readMap(i)
            sum += row.getStringByteLength(0)
            sum += row.getInt(1)
            sum += row.getStringByteLength(2)
            sum += row.getStringByteLength(3)
        }
        return sum
    }

    private fun scanFlatPrimitivesAccessor(bytes: ByteArray): Long {
        val map = bytes.toFlexMap()
        return (if (map.getBoolean(0)) 1L else 0L) +
            map.getInt(1) +
            map.getInt(2) +
            map.getDouble(3).toLong() +
            map.getFloat(4).toLong() +
            map.getInt(5) +
            map.getLong(6) +
            map.getStringByteLength(7) +
            map.getInt(8)
    }

    private fun scanEventLogAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        return root.getInt(0).toLong() +
            root.getStringByteLength(1) +
            root.getStringByteLength(2) +
            root.getMap(3).size +
            root.getMap(5).size +
            root.getStringByteLength(6) +
            (if (root.getBoolean(7)) 1 else 0) +
            root.getVector(8).size +
            root.getLong(9) +
            root.getLong(10)
    }

    private fun scanConfigSnapshotAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val features = root.getMap(5)
        return root.getLong(0) +
            (if (root.getBoolean(1)) 1 else 0) +
            root.getVector(2).size +
            root.getMap(3).size +
            root.getStringByteLength(4) +
            features.size +
            root.getMap(6).size +
            root.getInt(7)
    }

    private fun scanChatThreadAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val messages = root.getVector(1)
        val first = messages.readMap(0)
        return root.getMap(0).size.toLong() +
            messages.size +
            root.getVector(3).size +
            (if (root.getBoolean(4)) 1 else 0) +
            root.getLong(5) +
            root.getStringByteLength(6) +
            first.getMap(2).size +
            first.getStringByteLength(5)
    }

    private fun scanApiResponseAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val items = root.getVector(0)
        val first = items.readMap(0)
        return items.size.toLong() +
            root.getMap(2).size +
            root.getInt(3) +
            root.getInt(4) +
            root.getInt(5) +
            root.getInt(6) +
            items.size +
            first.getVector(0).size +
            first.getVector(4).size +
            first.getStringByteLength(6)
    }

    private fun scanCollectionHeavyAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        return root.getVector(1).size.toLong() +
            root.getVector(4).size +
            root.getVector(0).size +
            root.getVector(3).size +
            root.getMap(5).size +
            root.getMap(2).size
    }

    private fun scanNotificationInboxAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val items = root.getVector(2)
        var sum = root.getMap(0).size.toLong() +
            root.getStringByteLength(1) +
            root.getLong(3) +
            root.getInt(4) +
            root.getLong(5) +
            items.size
        val limit = minOf(items.size, 8)
        for (i in 0 until limit) {
            val item = items.readMap(i)
            sum += item.getVector(0).size
            sum += item.getLong(4)
            sum += item.getMap(5).size
            if (item.getBoolean(7)) sum++
            sum += item.getStringByteLength(9)
        }
        return sum
    }

    private fun scanOrderHistoryAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val orders = root.getVector(1)
        var sum = root.getStringByteLength(0).toLong() +
            root.getVector(2).size +
            root.getStringByteLength(3) +
            root.getMap(4).size +
            root.getLong(5) +
            orders.size
        val limit = minOf(orders.size, 6)
        for (i in 0 until limit) {
            val order = orders.readMap(i)
            val lines = order.getVector(2)
            sum += order.getLong(4)
            sum += order.getStringByteLength(6)
            sum += order.getLong(7)
            sum += order.getStringByteLength(8)
            sum += lines.size
        }
        return sum
    }

    private fun scanMediaLibraryAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val assets = root.getVector(1)
        var sum = root.getLong(0) +
            root.getLong(2) +
            root.getLong(3) +
            root.getMap(4).size +
            root.getVector(5).size +
            root.getStringByteLength(6) +
            assets.size
        val limit = minOf(assets.size, 8)
        for (i in 0 until limit) {
            val asset = assets.readMap(i)
            sum += asset.getLong(0)
            sum += asset.getStringByteLength(2)
            sum += asset.getVector(6).size
            sum += asset.getLong(9)
            sum += asset.getMap(10).size
        }
        return sum
    }

    private fun scanSearchResultsAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val hits = root.getVector(2)
        var sum = root.getMap(0).size.toLong() +
            root.getVector(1).size +
            root.getInt(3) +
            root.getInt(4) +
            root.getStringByteLength(5) +
            root.getInt(6) +
            root.getInt(7) +
            hits.size
        val limit = minOf(hits.size, 10)
        for (i in 0 until limit) {
            val hit = hits.readMap(i)
            sum += hit.getMap(0).size
            sum += hit.getVector(1).size
            sum += hit.getLong(2)
            sum += hit.getDouble(3).toLong()
            sum += hit.getStringByteLength(4)
            sum += hit.getStringByteLength(6)
        }
        return sum
    }

    private fun scanWorkoutSessionAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val samples = root.getVector(5)
        var sum = root.getInt(0).toLong() +
            root.getInt(1) +
            root.getDouble(3).toLong() +
            root.getInt(4) +
            root.getStringByteLength(6) +
            root.getStringByteLength(7) +
            root.getLong(9) +
            root.getMap(10).size +
            samples.size
        for (i in 0 until samples.size step 4) {
            val sample = samples.readMap(i)
            sum += sample.getInt(0)
            sum += sample.getInt(2)
            sum += sample.getInt(5)
            sum += sample.getDouble(6).toLong()
        }
        return sum
    }

    private fun scanTimeSeriesAccessor(bytes: ByteArray): Long {
        val map = bytes.toFlexMap()
        var sum = 0L
        sum += map.getInt(0)
        sum += map.getInt(1)
        sum += map.getDouble(2).toLong()
        sum += map.getDouble(3).toLong()
        sum += map.getDouble(4).toLong()
        sum += map.getStringByteLength(5)
        sum += map.getLong(6)
        val timestamps = map.getVector(7)
        timestamps.forEachLong { sum += it }
        val values = map.getVector(8)
        values.forEachDouble { sum += it.toLong() }
        return sum
    }

    private fun scanBankingLedgerAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val transactions = root.getVector(7)
        var sum = root.getLong(0) + root.getMap(1).size + root.getStringByteLength(2) +
            root.getStringByteLength(3) + root.getLong(4) + root.getVector(5).size +
            root.getMap(6).size + transactions.size
        for (i in 0 until minOf(transactions.size, 16)) {
            val tx = transactions.readMap(i)
            sum += tx.getLong(0) + tx.getStringByteLength(1) + tx.getStringByteLength(2) +
                tx.getLong(3) + tx.getStringByteLength(4) + tx.getLong(5) +
                tx.getVector(6).size + tx.getStringByteLength(7)
        }
        return sum
    }

    private fun scanRideHistoryAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val trips = root.getVector(3)
        var sum = root.getMap(0).size.toLong() + root.getStringByteLength(1) +
            root.getLong(2) + trips.size + root.getStringByteLength(4)
        for (i in 0 until minOf(trips.size, 10)) {
            val trip = trips.readMap(i)
            val points = trip.getVector(6)
            sum += trip.getDouble(0).toLong() + trip.getLong(1) + trip.getStringByteLength(2) +
                trip.getLong(3) + trip.getLong(4) + trip.getStringByteLength(5) +
                points.size + trip.getLong(7) + trip.getStringByteLength(8)
            for (p in 0 until points.size step 6) {
                val point = points.readMap(p)
                sum += point.getDouble(0).toLong() + point.getDouble(1).toLong() + point.getLong(2)
            }
        }
        return sum
    }

    private fun scanProjectBoardAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val columns = root.getVector(1)
        var sum = root.getLong(0) + columns.size + root.getVector(2).size +
            root.getMap(3).size + root.getStringByteLength(4) + root.getLong(5)
        for (i in 0 until columns.size) {
            val column = columns.readMap(i)
            val tasks = column.getVector(1)
            sum += column.getStringByteLength(0) + tasks.size + column.getInt(2)
            for (t in 0 until minOf(tasks.size, 4)) {
                val task = tasks.readMap(t)
                sum += task.getLong(0) + task.getVector(1).size + task.getLong(2) +
                    task.getVector(3).size + task.getInt(4) + task.getStringByteLength(5) +
                    task.getLong(6) + task.getStringByteLength(7)
            }
        }
        return sum
    }

    private fun scanDocumentCorpusAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val documents = root.getVector(0)
        var sum = documents.size.toLong() + root.getLong(1) + root.getLong(2) +
            root.getMap(3).size + root.getLong(4)
        for (i in 0 until minOf(documents.size, 6)) {
            val doc = documents.readMap(i)
            val revisions = doc.getVector(2)
            sum += doc.getLong(0) + doc.getLong(1) + revisions.size +
                doc.getVector(3).size + doc.getStringByteLength(4) + doc.getStringByteLength(5)
            for (r in 0 until minOf(revisions.size, 2)) {
                val revision = revisions.readMap(r)
                val segments = revision.getVector(4)
                sum += revision.getLong(0) + revision.getStringByteLength(1) +
                    revision.getLong(2) + revision.getLong(3) + segments.size
                for (s in 0 until minOf(segments.size, 3)) {
                    val segment = segments.readMap(s)
                    sum += segment.getStringByteLength(0) + segment.getInt(1) +
                        segment.getStringByteLength(2) + segment.getInt(3)
                }
            }
        }
        return sum
    }

    private fun scanSecurityAuditAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val events = root.getVector(1)
        var sum = root.getStringByteLength(0).toLong() + events.size + root.getLong(2) +
            root.getStringByteLength(3) + root.getMap(4).size
        for (i in 0 until minOf(events.size, 24)) {
            val event = events.readMap(i)
            sum += event.getLong(0) + event.getLong(1) + event.getStringByteLength(2) +
                event.getMap(3).size + event.getStringByteLength(4) +
                event.getDouble(5).toLong() + if (event.getBoolean(6)) 1 else 0 +
                event.getLong(7) + event.getStringByteLength(8)
        }
        return sum
    }

    private fun scanGraphSnapshotAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val edges = root.getVector(0)
        val nodes = root.getVector(2)
        var sum = edges.size.toLong() + root.getLong(1) + nodes.size +
            root.getMap(3).size + root.getLong(4)
        for (i in 0 until minOf(nodes.size, 16)) {
            val node = nodes.readMap(i)
            sum += node.getLong(0) + node.getStringByteLength(1) +
                node.getVector(2).size + node.getMap(3).size
        }
        for (i in 0 until minOf(edges.size, 24)) {
            val edge = edges.readMap(i)
            sum += edge.getLong(0) + edge.getStringByteLength(1) +
                edge.getMap(2).size + edge.getDouble(3).toLong() + edge.getLong(4)
        }
        return sum
    }

    private fun scanRecommendationFeedAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val cards = root.getVector(0)
        var sum = cards.size.toLong() + root.getStringByteLength(1) + root.getLong(2) +
            root.getStringByteLength(3) + root.getLong(4)
        for (i in 0 until minOf(cards.size, 18)) {
            val card = cards.readMap(i)
            val reasons = card.getVector(4)
            sum += card.getVector(0).size + card.getLong(1) + card.getStringByteLength(2) +
                card.getMap(3).size + reasons.size + card.getDouble(5).toLong() +
                card.getStringByteLength(6)
            for (r in 0 until reasons.size) {
                val reason = reasons.readMap(r)
                sum += reason.getStringByteLength(0) + reason.getDouble(1).toLong()
            }
        }
        return sum
    }

    private fun scanGameWorldAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val entities = root.getVector(0)
        val quests = root.getVector(2)
        var sum = entities.size.toLong() + root.getLong(1) + quests.size + root.getLong(3) + root.getLong(4)
        for (i in 0 until minOf(entities.size, 16)) {
            val entity = entities.readMap(i)
            val inventory = entity.getVector(2)
            val position = entity.getMap(4)
            sum += entity.getMap(0).size + entity.getLong(1) + inventory.size +
                entity.getStringByteLength(3) + position.getDouble(0).toLong() +
                position.getDouble(1).toLong() + position.getDouble(2).toLong() +
                entity.getStringByteLength(5)
        }
        for (i in 0 until quests.size) {
            val quest = quests.readMap(i)
            sum += if (quest.getBoolean(0)) 1 else 0
            sum += quest.getMap(1).size + quest.getLong(2) + quest.getStringByteLength(3)
        }
        return sum
    }

    private fun scanIoTFleetAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val alerts = root.getVector(0)
        val devices = root.getVector(1)
        var sum = alerts.size.toLong() + devices.size + root.getStringByteLength(2) +
            root.getStringByteLength(3) + root.getLong(4)
        for (i in 0 until minOf(devices.size, 16)) {
            val device = devices.readMap(i)
            val readings = device.getVector(4)
            sum += device.getStringByteLength(0) + device.getStringByteLength(1) +
                device.getMap(2).size + if (device.getBoolean(3)) 1 else 0 +
                readings.size + device.getVector(5).size
            for (r in 0 until readings.size step 3) {
                val reading = readings.readMap(r)
                sum += reading.getStringByteLength(0) + reading.getInt(1) +
                    reading.getLong(2) + reading.getDouble(3).toLong()
            }
        }
        return sum
    }

    private fun scanCRMPortfolioAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val accounts = root.getVector(0)
        var sum = accounts.size.toLong() + root.getLong(1) + root.getStringByteLength(2) +
            root.getStringByteLength(3) + root.getLong(4)
        for (i in 0 until minOf(accounts.size, 10)) {
            val account = accounts.readMap(i)
            val contacts = account.getVector(1)
            val opportunities = account.getVector(4)
            sum += account.getLong(0) + contacts.size + account.getStringByteLength(2) +
                account.getStringByteLength(3) + opportunities.size + account.getVector(5).size
            for (o in 0 until opportunities.size) {
                val opp = opportunities.readMap(o)
                sum += opp.getLong(0) + opp.getLong(1) + opp.getDouble(2).toLong() +
                    opp.getStringByteLength(3) + opp.getStringByteLength(4)
            }
        }
        return sum
    }

    private fun scanTravelItineraryAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val activities = root.getVector(0)
        val flights = root.getVector(1)
        val hotels = root.getVector(2)
        var sum = activities.size.toLong() + flights.size + hotels.size + root.getLong(3) + root.getLong(4)
        for (i in 0 until flights.size) {
            val flight = flights.readMap(i)
            sum += flight.getStringByteLength(0) + flight.getLong(1) + flight.getStringByteLength(2) +
                flight.getStringByteLength(3) + flight.getLong(4) + flight.getStringByteLength(5) +
                flight.getVector(6).size
        }
        for (i in 0 until minOf(activities.size, 8)) {
            val activity = activities.readMap(i)
            sum += activity.getStringByteLength(0) + activity.getLong(1) +
                activity.getStringByteLength(2) + activity.getLong(3) + activity.getVector(4).size
        }
        return sum
    }

    private fun scanCourseRosterAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val modules = root.getVector(2)
        val students = root.getVector(3)
        var sum = root.getLong(0) + root.getVector(1).size + modules.size +
            students.size + root.getStringByteLength(4)
        for (i in 0 until modules.size) {
            val module = modules.readMap(i)
            sum += module.getVector(0).size + module.getLong(1) +
                module.getStringByteLength(2) + module.getDouble(3).toLong()
        }
        for (i in 0 until minOf(students.size, 16)) {
            val student = students.readMap(i)
            sum += student.getStringByteLength(0) + student.getMap(1).size +
                student.getLong(2) + student.getStringByteLength(3) +
                student.getLong(4) + student.getVector(5).size
        }
        return sum
    }

    private fun scanShipmentBatchAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val shipments = root.getVector(3)
        var sum = root.getLong(0) + root.getStringByteLength(1) + root.getLong(2) +
            shipments.size + root.getStringByteLength(4)
        for (i in 0 until minOf(shipments.size, 16)) {
            val shipment = shipments.readMap(i)
            val events = shipment.getVector(1)
            val packages = shipment.getVector(2)
            sum += shipment.getStringByteLength(0) + events.size + packages.size +
                shipment.getStringByteLength(3) + shipment.getLong(4) + shipment.getStringByteLength(5)
            for (p in 0 until packages.size) {
                val pkg = packages.readMap(p)
                sum += pkg.getInt(0) + pkg.getLong(1) + pkg.getInt(2) + pkg.getInt(3)
            }
        }
        return sum
    }

    private fun scanMarketDataAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val instruments = root.getVector(0)
        var sum = instruments.size.toLong() + root.getStringByteLength(1) + root.getLong(2) + root.getLong(3)
        for (i in 0 until minOf(instruments.size, 10)) {
            val instrument = instruments.readMap(i)
            val orders = instrument.getVector(3)
            val ticks = instrument.getVector(5)
            sum += instrument.getDouble(0).toLong() + instrument.getDouble(1).toLong() +
                instrument.getDouble(2).toLong() + orders.size + instrument.getStringByteLength(4) + ticks.size
            for (o in 0 until orders.size step 5) {
                val order = orders.readMap(o)
                sum += order.getInt(0) + order.getDouble(1).toLong() + order.getLong(2) + order.getStringByteLength(3)
            }
            for (t in 0 until ticks.size step 4) {
                val tick = ticks.readMap(t)
                sum += tick.getDouble(0).toLong() + tick.getLong(1) + tick.getLong(2)
            }
        }
        return sum
    }

    private fun scanSocialGraphDeltaAccessor(bytes: ByteArray): Long {
        val root = bytes.toFlexMap()
        val edges = root.getVector(1)
        val interactions = root.getVector(3)
        val users = root.getVector(4)
        var sum = root.getLong(0) + edges.size + root.getLong(2) + interactions.size + users.size
        for (i in 0 until minOf(users.size, 16)) {
            val user = users.readMap(i)
            sum += user.getStringByteLength(0) + user.getInt(1) + user.getVector(2).size +
                user.getLong(3) + user.getStringByteLength(4)
        }
        for (i in 0 until minOf(edges.size, 24)) {
            val edge = edges.readMap(i)
            sum += edge.getLong(0) + edge.getStringByteLength(1) +
                edge.getDouble(2).toLong() + edge.getLong(3)
        }
        for (i in 0 until minOf(interactions.size, 16)) {
            val interaction = interactions.readMap(i)
            sum += interaction.getMap(0).size + interaction.getLong(1) +
                interaction.getLong(2) + interaction.getStringByteLength(3) + interaction.getLong(4)
        }
        return sum
    }

    private fun decodeTinyStatusKey(bytes: ByteArray): Long {
        val root = getRoot(bytes).toMap()
        return root.getLong("createdAt") +
            root.getLong("id") +
            root.getDouble("score").toLong() +
            root.getStringByteLength("status") +
            root.getStringByteLength("username") +
            if (root.getBoolean("verified")) 1 else 0
    }

    private fun decodeTinyStatusPartialKey(bytes: ByteArray): Long {
        val root = getRoot(bytes).toMap()
        return root.getDouble("score").toLong() +
            root.getStringByteLength("status") +
            if (root.getBoolean("verified")) 1 else 0
    }

    private fun encodeSparseOptionsFlex(present: Int): ByteArray {
        return FlexBufferPool.encode {
            val map = startMap()
            repeat(present) { i ->
                set("present_%04d".format(i), i)
            }
            endMap(map)
        }
    }

    private fun decodeSparseMissing(bytes: ByteArray, missingKeys: List<String>): Int {
        val root = getRoot(bytes).toMap()
        var hits = 0
        for (key in missingKeys) {
            if (root.indexOf(key) >= 0) hits++
        }
        return hits
    }

    private fun encodeMapNKeys(n: Int): ByteArray {
        return FlexBufferPool.encode {
            val map = startMap()
            repeat(n) { i ->
                set("field_%04d".format(i), i)
            }
            endMap(map)
        }
    }

    private fun wideRandomKeys(n: Int, lookups: Int): List<String> {
        return List(lookups) { i -> "field_%04d".format((i * 131 + 17) % n) }
    }

    private fun decodeWideRandomKeys(bytes: ByteArray, keys: List<String>): Long {
        val root = getRoot(bytes).toMap()
        var sum = 0L
        keys.forEach { key -> sum += root.getLong(key) }
        return sum
    }

    private fun decodeWideSequentialIndexes(bytes: ByteArray, reads: Int): Long {
        val root = getRoot(bytes).toMap()
        val buf = root.buf; val end = root.end; val bw = root.byteWidth
        val tb = end + root.size * bw
        var sum = 0L
        repeat(reads) { index -> sum += FlexRead.getLong(buf, end, bw, tb, index) }
        return sum
    }

    private fun encodeUniqueStringPayload(share: Boolean, rows: Int, len: Int): ByteArray {
        val builder = FlexBuffersBuilder(
            initialCapacity = rows * (len + 128),
            shareFlag = if (share) FlexBuffersBuilder.SHARE_KEYS_AND_STRINGS else FlexBuffersBuilder.SHARE_NONE
        )
        val root = builder.startMap()
        val items = builder.startVector()
        repeat(rows) { index ->
            val body = adversarialText(index * 7, len) + "_$index"
            val item = builder.startMap()
            builder.set("alsoUnique", "row_${index}_$body")
            builder.set("id", index)
            builder.set("unique", body)
            builder.endMap(item, presorted = true)
        }
        builder.endVector("items", items)
        builder.endMap(root, presorted = true)
        return builder.finish().toByteArray()
    }

    private fun runCppAdversarialHarness(): kotlin.collections.Map<String, Double> {
        val benchDir = findCppBenchDir()
        val source = File(benchDir, "flexbuffer_bench.cpp")
        val binary = File(System.getProperty("user.dir"), "build/tmp/flexbufferBench/flexbuffer_bench")
        if (!binary.canExecute() || binary.lastModified() < source.lastModified()) {
            binary.parentFile.mkdirs()
            val compile = ProcessBuilder(
                "clang++",
                "-O2",
                "-std=c++17",
                "-I",
                "../../../.github_modules/flatbuffers/include",
                "flexbuffer_bench.cpp",
                "-o",
                binary.absolutePath
            ).directory(benchDir).redirectErrorStream(true).start()
            val compileOutput = compile.inputStream.bufferedReader().readText()
            val compileExit = compile.waitFor()
            assertEquals(0, compileExit, "C++ harness compile failed:\n$compileOutput")
        }

        val process = ProcessBuilder(binary.absolutePath, "--adversarial", "--verify")
            .directory(benchDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        assertEquals(0, exit, "C++ adversarial harness failed:\n$output")

        println("\nC++ adversarial harness excerpt:")
        output.lineSequence()
            .filter { it.contains("FlexBuffer") || it.contains("Flex encode") || it.contains("lost ") || it.contains("Adversarial:") }
            .take(24)
            .forEach { println("  cpp> $it") }

        val metricRegex = Regex("""^\s+(.+?)\s+([0-9]+(?:\.[0-9]+)?)\s+us/op""")
        return output.lineSequence().mapNotNull { line ->
            metricRegex.find(line)?.let { match ->
                match.groupValues[1].trim() to match.groupValues[2].toDouble()
            }
        }.toMap()
    }

    private fun findCppBenchDir(): File {
        val candidates = listOf(
            File("cpp/bench"),
            File("reaktor-flexbuffer/cpp/bench"),
            File("../reaktor-flexbuffer/cpp/bench"),
            File(System.getProperty("user.dir"), "cpp/bench"),
            File(System.getProperty("user.dir"), "reaktor-flexbuffer/cpp/bench")
        )
        return candidates.firstOrNull { File(it, "flexbuffer_bench.cpp").isFile }?.canonicalFile
            ?: error("Could not locate reaktor-flexbuffer/cpp/bench/flexbuffer_bench.cpp from ${System.getProperty("user.dir")}")
    }

    private fun kotlin.collections.Map<String, Double>.requireMetric(label: String): Double {
        return this[label] ?: error("C++ metric '$label' not found. Available metrics: ${keys.sorted()}")
    }
}
