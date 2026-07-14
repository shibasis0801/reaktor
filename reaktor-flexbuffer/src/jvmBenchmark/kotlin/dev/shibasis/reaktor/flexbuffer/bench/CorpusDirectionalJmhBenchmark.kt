@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.shibasis.reaktor.flexbuffer.bench

import dev.shibasis.reaktor.flexbuffer.generated.ReaktorFlexbufferCoders
import dev.shibasis.reaktor.flexbuffer.*
import dev.shibasis.reaktor.flexbuffer.core.FlexCoder
import dev.shibasis.reaktor.flexbuffer.core.FlexCoderRegistry
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import java.util.concurrent.TimeUnit

/**
 * Directional encode/decode sweep over every production-shaped benchmark fixture.
 *
 * The adapter captures a concrete value, serializer, and generated coder together, preserving
 * their generic relationship without unchecked casts in the measured path. Decode payloads and
 * all correctness work are prepared in [setup], outside the timed operation.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class CorpusDirectionalJmhBenchmark {
    @Param(
        "UserProfile",
        "ApiResponse",
        "EventLog",
        "ChatThread",
        "ConfigSnapshot",
        "TimeSeriesChunk",
        "NotificationInbox",
        "OrderHistory",
        "MediaLibrary",
        "SearchResultsPage",
        "WorkoutSession",
        "BankingLedger",
        "RideHistory",
        "ProjectBoardSnapshot",
        "DocumentCorpus",
        "SecurityAuditTrail",
        "GraphSnapshot",
        "RecommendationFeed",
        "GameWorldState",
        "IoTFleetSnapshot",
        "CRMPortfolio",
        "TravelItinerary",
        "CourseRoster",
        "ShipmentBatch",
        "MarketDataSnapshot",
        "SocialGraphDelta",
    )
    var caseName: String = "UserProfile"

    private val json = Json { encodeDefaults = true }
    private val protoBuf = ProtoBuf { encodeDefaults = true }

    private lateinit var fixture: CorpusFixture
    private lateinit var directFlexBytes: ByteArray
    private lateinit var jsonText: String
    private lateinit var protoBufBytes: ByteArray

    @JvmField var expectedChecksum: Int = 0
    @JvmField var directFlexPayloadBytes: Int = 0
    @JvmField var jsonUtf8PayloadBytes: Int = 0
    @JvmField var protoBufPayloadBytes: Int = 0

    @Setup
    open fun setup() {
        ReaktorFlexbufferCoders.register()
        fixture = corpusFixture(caseName)

        directFlexBytes = fixture.directFlexEncode()
        jsonText = fixture.jsonEncode(json)
        protoBufBytes = fixture.protoBufEncode(protoBuf)

        expectedChecksum = fixture.expectedChecksum
        fixture.verifyDecoded(fixture.directFlexDecode(directFlexBytes), "direct FlexCoder")
        fixture.verifyDecoded(fixture.jsonDecode(json, jsonText), "JSON")
        fixture.verifyDecoded(fixture.protoBufDecode(protoBuf, protoBufBytes), "ProtoBuf")

        directFlexPayloadBytes = directFlexBytes.size
        jsonUtf8PayloadBytes = jsonText.encodeToByteArray().size
        protoBufPayloadBytes = protoBufBytes.size
        check(expectedChecksum == fixture.expectedChecksum)
    }

    @Benchmark
    open fun generatedFlexCoderEncodeOnly(): ByteArray = fixture.directFlexEncode()

    @Benchmark
    open fun generatedFlexCoderDecodeOnly(): Any = fixture.directFlexDecode(directFlexBytes)

    @Benchmark
    open fun jsonEncodeOnly(): String = fixture.jsonEncode(json)

    @Benchmark
    open fun jsonDecodeOnly(): Any = fixture.jsonDecode(json, jsonText)

    @Benchmark
    open fun protoBufEncodeOnly(): ByteArray = fixture.protoBufEncode(protoBuf)

    @Benchmark
    open fun protoBufDecodeOnly(): Any = fixture.protoBufDecode(protoBuf, protoBufBytes)
}

/**
 * Raw kotlinx.serialization FlexBuffer sweep.
 *
 * This state must be selected independently from [CorpusDirectionalJmhBenchmark]. JMH forks each
 * benchmark separately, and setup clears the process-global coder registry before both payload
 * creation and every measured raw serializer call.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class RawCorpusDirectionalJmhBenchmark {
    @Param(
        "UserProfile",
        "ApiResponse",
        "EventLog",
        "ChatThread",
        "ConfigSnapshot",
        "TimeSeriesChunk",
        "NotificationInbox",
        "OrderHistory",
        "MediaLibrary",
        "SearchResultsPage",
        "WorkoutSession",
        "BankingLedger",
        "RideHistory",
        "ProjectBoardSnapshot",
        "DocumentCorpus",
        "SecurityAuditTrail",
        "GraphSnapshot",
        "RecommendationFeed",
        "GameWorldState",
        "IoTFleetSnapshot",
        "CRMPortfolio",
        "TravelItinerary",
        "CourseRoster",
        "ShipmentBatch",
        "MarketDataSnapshot",
        "SocialGraphDelta",
    )
    var caseName: String = "UserProfile"

    private val json = Json { encodeDefaults = true }
    private val protoBuf = ProtoBuf { encodeDefaults = true }

    private lateinit var fixture: CorpusFixture
    private lateinit var rawFlexBytes: ByteArray

    @JvmField var expectedChecksum: Int = 0
    @JvmField var directFlexPayloadBytes: Int = 0
    @JvmField var rawFlexPayloadBytes: Int = 0
    @JvmField var jsonUtf8PayloadBytes: Int = 0
    @JvmField var protoBufPayloadBytes: Int = 0

    @Setup
    open fun setup() {
        FlexCoderRegistry.clear()
        fixture = corpusFixture(caseName)
        assertRawRegistryIsEmpty()

        rawFlexBytes = fixture.rawFlexEncode()
        fixture.verifyDecoded(fixture.rawFlexDecode(rawFlexBytes), "raw kotlinx FlexBuffer")
        assertRawRegistryIsEmpty()

        // Size probes are setup-only. Calling an explicit coder never registers it globally.
        directFlexPayloadBytes = fixture.directFlexEncode().size
        rawFlexPayloadBytes = rawFlexBytes.size
        jsonUtf8PayloadBytes = fixture.jsonEncode(json).encodeToByteArray().size
        protoBufPayloadBytes = fixture.protoBufEncode(protoBuf).size
        expectedChecksum = fixture.expectedChecksum
        assertRawRegistryIsEmpty()

        println(
            "CORPUS_WIRE_SIZE|$caseName|direct=$directFlexPayloadBytes|raw=$rawFlexPayloadBytes|" +
                "jsonUtf8=$jsonUtf8PayloadBytes|protobuf=$protoBufPayloadBytes|checksum=$expectedChecksum",
        )
    }

    @Benchmark
    open fun rawKotlinxFlexEncodeOnly(): ByteArray = fixture.rawFlexEncode()

    @Benchmark
    open fun rawKotlinxFlexDecodeOnly(): Any = fixture.rawFlexDecode(rawFlexBytes)

    private fun assertRawRegistryIsEmpty() {
        check(FlexCoderRegistry.getBySerialName<Any>(fixture.serialName) == null) {
            "Generated coder registry is populated for raw corpus case ${fixture.name}"
        }
    }
}

private interface CorpusFixture {
    val name: String
    val serialName: String
    val expectedChecksum: Int

    fun directFlexEncode(): ByteArray
    fun directFlexDecode(bytes: ByteArray): Any
    fun rawFlexEncode(): ByteArray
    fun rawFlexDecode(bytes: ByteArray): Any
    fun jsonEncode(json: Json): String
    fun jsonDecode(json: Json, text: String): Any
    fun protoBufEncode(protoBuf: ProtoBuf): ByteArray
    fun protoBufDecode(protoBuf: ProtoBuf, bytes: ByteArray): Any
    fun verifyDecoded(decoded: Any, format: String)
}

private class TypedCorpusFixture<T : Any>(
    override val name: String,
    private val value: T,
    private val serializer: KSerializer<T>,
    // Object-erased so the Java-21 JMH reflection generator does not resolve the
    // Java-25 production FlexCoder class while inspecting this helper's fields.
    private val coder: Any,
) : CorpusFixture {
    override val serialName: String = serializer.descriptor.serialName
    override val expectedChecksum: Int = value.hashCode()

    override fun directFlexEncode(): ByteArray {
        @Suppress("UNCHECKED_CAST")
        val typedCoder = coder as FlexCoder<T>
        return FlexBuffers.encode(typedCoder, value)
    }

    override fun directFlexDecode(bytes: ByteArray): Any {
        @Suppress("UNCHECKED_CAST")
        val typedCoder = coder as FlexCoder<T>
        return FlexBuffers.decode(typedCoder, bytes)
    }

    override fun rawFlexEncode(): ByteArray = FlexBuffers.encode(serializer, value)

    override fun rawFlexDecode(bytes: ByteArray): Any = FlexBuffers.decode(serializer, bytes)

    override fun jsonEncode(json: Json): String = json.encodeToString(serializer, value)

    override fun jsonDecode(json: Json, text: String): Any = json.decodeFromString(serializer, text)

    override fun protoBufEncode(protoBuf: ProtoBuf): ByteArray =
        protoBuf.encodeToByteArray(serializer, value)

    override fun protoBufDecode(protoBuf: ProtoBuf, bytes: ByteArray): Any =
        protoBuf.decodeFromByteArray(serializer, bytes)

    override fun verifyDecoded(decoded: Any, format: String) {
        check(decoded == value) { "$format equality failed for $name" }
        check(decoded.hashCode() == expectedChecksum) {
            "$format checksum failed for $name: ${decoded.hashCode()} != $expectedChecksum"
        }
    }
}

private fun corpusFixture(name: String): CorpusFixture =
    when (name) {
        "UserProfile" -> typedFixture(name, BenchmarkData.userProfile(), BenchUserProfile.serializer(), BenchUserProfileFlexCoder)
        "ApiResponse" -> typedFixture(name, BenchmarkData.apiResponse(), BenchApiResponse.serializer(), BenchApiResponseFlexCoder)
        "EventLog" -> typedFixture(name, BenchmarkData.eventLog(), BenchEventLog.serializer(), BenchEventLogFlexCoder)
        "ChatThread" -> typedFixture(name, BenchmarkData.chatThread(), BenchChatThread.serializer(), BenchChatThreadFlexCoder)
        "ConfigSnapshot" -> typedFixture(name, BenchmarkData.configSnapshot(), BenchConfigSnapshot.serializer(), BenchConfigSnapshotFlexCoder)
        "TimeSeriesChunk" -> typedFixture(name, BenchmarkData.timeSeriesChunk(), BenchTimeSeriesChunk.serializer(), BenchTimeSeriesChunkFlexCoder)
        "NotificationInbox" -> typedFixture(name, BenchmarkData.notificationInbox(), BenchNotificationInbox.serializer(), BenchNotificationInboxFlexCoder)
        "OrderHistory" -> typedFixture(name, BenchmarkData.orderHistory(), BenchOrderHistory.serializer(), BenchOrderHistoryFlexCoder)
        "MediaLibrary" -> typedFixture(name, BenchmarkData.mediaLibrary(), BenchMediaLibrary.serializer(), BenchMediaLibraryFlexCoder)
        "SearchResultsPage" -> typedFixture(name, BenchmarkData.searchResultsPage(), BenchSearchResultsPage.serializer(), BenchSearchResultsPageFlexCoder)
        "WorkoutSession" -> typedFixture(name, BenchmarkData.workoutSession(), BenchWorkoutSession.serializer(), BenchWorkoutSessionFlexCoder)
        "BankingLedger" -> typedFixture(name, BenchmarkData.bankingLedger(), BenchBankingLedger.serializer(), BenchBankingLedgerFlexCoder)
        "RideHistory" -> typedFixture(name, BenchmarkData.rideHistory(), BenchRideHistory.serializer(), BenchRideHistoryFlexCoder)
        "ProjectBoardSnapshot" -> typedFixture(name, BenchmarkData.projectBoardSnapshot(), BenchProjectBoardSnapshot.serializer(), BenchProjectBoardSnapshotFlexCoder)
        "DocumentCorpus" -> typedFixture(name, BenchmarkData.documentCorpus(), BenchDocumentCorpus.serializer(), BenchDocumentCorpusFlexCoder)
        "SecurityAuditTrail" -> typedFixture(name, BenchmarkData.securityAuditTrail(), BenchSecurityAuditTrail.serializer(), BenchSecurityAuditTrailFlexCoder)
        "GraphSnapshot" -> typedFixture(name, BenchmarkData.graphSnapshot(), BenchGraphSnapshot.serializer(), BenchGraphSnapshotFlexCoder)
        "RecommendationFeed" -> typedFixture(name, BenchmarkData.recommendationFeed(), BenchRecommendationFeed.serializer(), BenchRecommendationFeedFlexCoder)
        "GameWorldState" -> typedFixture(name, BenchmarkData.gameWorldState(), BenchGameWorldState.serializer(), BenchGameWorldStateFlexCoder)
        "IoTFleetSnapshot" -> typedFixture(name, BenchmarkData.iotFleetSnapshot(), BenchIoTFleetSnapshot.serializer(), BenchIoTFleetSnapshotFlexCoder)
        "CRMPortfolio" -> typedFixture(name, BenchmarkData.crmPortfolio(), BenchCRMPortfolio.serializer(), BenchCRMPortfolioFlexCoder)
        "TravelItinerary" -> typedFixture(name, BenchmarkData.travelItinerary(), BenchTravelItinerary.serializer(), BenchTravelItineraryFlexCoder)
        "CourseRoster" -> typedFixture(name, BenchmarkData.courseRoster(), BenchCourseRoster.serializer(), BenchCourseRosterFlexCoder)
        "ShipmentBatch" -> typedFixture(name, BenchmarkData.shipmentBatch(), BenchShipmentBatch.serializer(), BenchShipmentBatchFlexCoder)
        "MarketDataSnapshot" -> typedFixture(name, BenchmarkData.marketDataSnapshot(), BenchMarketDataSnapshot.serializer(), BenchMarketDataSnapshotFlexCoder)
        "SocialGraphDelta" -> typedFixture(name, BenchmarkData.socialGraphDelta(), BenchSocialGraphDelta.serializer(), BenchSocialGraphDeltaFlexCoder)
        else -> error("Unknown corpus case $name")
    }

private fun <T : Any> typedFixture(
    name: String,
    value: T,
    serializer: KSerializer<T>,
    coder: Any,
): CorpusFixture = TypedCorpusFixture(name, value, serializer, coder)
