@file:OptIn(
    ExperimentalUnsignedTypes::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package dev.shibasis.reaktor.flexbuffer.bench

import dev.shibasis.reaktor.core.InnerNestedData
import dev.shibasis.reaktor.core.InnerNestedDataFlexCoder
import dev.shibasis.reaktor.flexbuffer.generated.ReaktorFlexbufferCoders
import dev.shibasis.reaktor.flexbuffer.BenchApiResponse
import dev.shibasis.reaktor.flexbuffer.BenchApiResponseFlexCoder
import dev.shibasis.reaktor.flexbuffer.BenchTimeSeriesChunk
import dev.shibasis.reaktor.flexbuffer.BenchTimeSeriesChunkFlexCoder
import dev.shibasis.reaktor.flexbuffer.BenchUserProfile
import dev.shibasis.reaktor.flexbuffer.BenchUserProfileFlexCoder
import dev.shibasis.reaktor.flexbuffer.BenchmarkData
import dev.shibasis.reaktor.flexbuffer.asBenchApiResponse
import dev.shibasis.reaktor.flexbuffer.asBenchTimeSeriesChunk
import dev.shibasis.reaktor.flexbuffer.asBenchUserProfile
import dev.shibasis.reaktor.flexbuffer.core.FlexCoderRegistry
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import dev.shibasis.reaktor.flexbuffer.core.toFlexMap
import dev.shibasis.reaktor.flexbuffer.flatbuffers.FlexBuffersBuilder
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class FlexBufferJmhBenchmark {
    private companion object {
        private const val CALLER_OWNED_CAPACITY = 16 * 1024
    }

    @Param("UserProfile", "ApiResponse", "TimeSeries")
    var caseName: String = "UserProfile"

    private val json = Json { encodeDefaults = true }

    private val protoBuf = ProtoBuf { encodeDefaults = true }

    private lateinit var userProfile: Any
    private lateinit var apiResponse: Any
    private lateinit var timeSeries: Any

    private lateinit var userFlex: ByteArray
    private lateinit var apiFlex: ByteArray
    private lateinit var timeSeriesFlex: ByteArray

    private lateinit var userSerializerRoutedFlex: ByteArray
    private lateinit var apiSerializerRoutedFlex: ByteArray
    private lateinit var timeSeriesSerializerRoutedFlex: ByteArray

    private lateinit var userJson: String
    private lateinit var apiJson: String
    private lateinit var timeSeriesJson: String

    private lateinit var userProto: ByteArray
    private lateinit var apiProto: ByteArray
    private lateinit var timeSeriesProto: ByteArray

    // Kept as Object so the Java-21 JMH reflection generator never resolves the Java-25
    // production class while inspecting state fields. The scored fork runs on Java 25.
    private lateinit var callerOwnedBuilder: Any
    private lateinit var callerOwnedUserBytes: ByteArray
    private lateinit var callerOwnedApiBytes: ByteArray
    private lateinit var callerOwnedTimeSeriesBytes: ByteArray
    private var callerOwnedUserLimit: Int = 0
    private var callerOwnedApiLimit: Int = 0
    private var callerOwnedTimeSeriesLimit: Int = 0

    /** Exact encoded sizes populated outside measurement for result interpretation. */
    @JvmField var generatedDirectPayloadBytes: Int = 0
    @JvmField var serializerRoutedPayloadBytes: Int = 0
    @JvmField var jsonUtf8PayloadBytes: Int = 0
    @JvmField var protoBufPayloadBytes: Int = 0
    @JvmField var callerOwnedPayloadBytes: Int = 0

    @Setup
    open fun setup() {
        ReaktorFlexbufferCoders.register()
        assertModuleFlexCodersRegistered()

        userProfile = BenchmarkData.userProfile()
        apiResponse = BenchmarkData.apiResponse()
        timeSeries = BenchmarkData.timeSeriesChunk()

        // Explicit-coder payloads are the stable decode inputs for the generated tiers.
        userFlex = FlexBuffers.encode(BenchUserProfileFlexCoder, userProfile as BenchUserProfile)
        apiFlex = FlexBuffers.encode(BenchApiResponseFlexCoder, apiResponse as BenchApiResponse)
        timeSeriesFlex = FlexBuffers.encode(BenchTimeSeriesChunkFlexCoder, timeSeries as BenchTimeSeriesChunk)

        // These calls must hit the generated coder through the serial-name registry.
        userSerializerRoutedFlex = FlexBuffers.encode(BenchUserProfile.serializer(), userProfile as BenchUserProfile)
        apiSerializerRoutedFlex = FlexBuffers.encode(BenchApiResponse.serializer(), apiResponse as BenchApiResponse)
        timeSeriesSerializerRoutedFlex = FlexBuffers.encode(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk)

        userJson = json.encodeToString(BenchUserProfile.serializer(), userProfile as BenchUserProfile)
        apiJson = json.encodeToString(BenchApiResponse.serializer(), apiResponse as BenchApiResponse)
        timeSeriesJson = json.encodeToString(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk)

        userProto = protoBuf.encodeToByteArray(BenchUserProfile.serializer(), userProfile as BenchUserProfile)
        apiProto = protoBuf.encodeToByteArray(BenchApiResponse.serializer(), apiResponse as BenchApiResponse)
        timeSeriesProto = protoBuf.encodeToByteArray(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk)

        callerOwnedBuilder = FlexBuffersBuilder(CALLER_OWNED_CAPACITY, FlexBuffersBuilder.SHARE_KEYS)
        FlexBuffersBuilder(CALLER_OWNED_CAPACITY, FlexBuffersBuilder.SHARE_KEYS).also { builder ->
            BenchUserProfileFlexCoder.encodeRoot(builder, userProfile as BenchUserProfile)
            callerOwnedUserLimit = builder.finishedLimit()
            callerOwnedUserBytes = builder.finishedBytes()
        }
        FlexBuffersBuilder(CALLER_OWNED_CAPACITY, FlexBuffersBuilder.SHARE_KEYS).also { builder ->
            BenchApiResponseFlexCoder.encodeRoot(builder, apiResponse as BenchApiResponse)
            callerOwnedApiLimit = builder.finishedLimit()
            callerOwnedApiBytes = builder.finishedBytes()
        }
        FlexBuffersBuilder(CALLER_OWNED_CAPACITY, FlexBuffersBuilder.SHARE_KEYS).also { builder ->
            BenchTimeSeriesChunkFlexCoder.encodeRoot(builder, timeSeries as BenchTimeSeriesChunk)
            callerOwnedTimeSeriesLimit = builder.finishedLimit()
            callerOwnedTimeSeriesBytes = builder.finishedBytes()
        }

        check(userFlex.contentEquals(userSerializerRoutedFlex))
        check(apiFlex.contentEquals(apiSerializerRoutedFlex))
        check(timeSeriesFlex.contentEquals(timeSeriesSerializerRoutedFlex))
        check(userFlex.contentEqualsPrefix(callerOwnedUserBytes, callerOwnedUserLimit))
        check(apiFlex.contentEqualsPrefix(callerOwnedApiBytes, callerOwnedApiLimit))
        check(timeSeriesFlex.contentEqualsPrefix(callerOwnedTimeSeriesBytes, callerOwnedTimeSeriesLimit))

        // Keep the generated-accessor workloads semantically tied to the full-decode
        // checksums. Setup is outside the measured region, so these guards are free.
        check(scanGeneratedUserProfileAccessor(userFlex) == checksumUser(userProfile))
        check(scanGeneratedApiResponseAccessor(apiFlex) == checksumApi(apiResponse))
        check(scanGeneratedTimeSeriesAccessor(timeSeriesFlex) == checksumTimeSeries(timeSeries))
        check(scanEquivalentUserProfileAccessor(userFlex) == checksumUser(userProfile))
        check(scanEquivalentApiResponseAccessor(apiFlex) == checksumApi(apiResponse))
        check(scanEquivalentTimeSeriesAccessor(timeSeriesFlex) == checksumTimeSeries(timeSeries))
        check(FlexBuffers.decode(BenchUserProfileFlexCoder, userFlex) == userProfile)
        check(FlexBuffers.decode(BenchApiResponseFlexCoder, apiFlex) == apiResponse)
        check(FlexBuffers.decode(BenchTimeSeriesChunkFlexCoder, timeSeriesFlex) == timeSeries)
        check(FlexBuffers.decode(BenchUserProfile.serializer(), userSerializerRoutedFlex) == userProfile)
        check(FlexBuffers.decode(BenchApiResponse.serializer(), apiSerializerRoutedFlex) == apiResponse)
        check(FlexBuffers.decode(BenchTimeSeriesChunk.serializer(), timeSeriesSerializerRoutedFlex) == timeSeries)
        check(json.decodeFromString(BenchUserProfile.serializer(), userJson) == userProfile)
        check(json.decodeFromString(BenchApiResponse.serializer(), apiJson) == apiResponse)
        check(json.decodeFromString(BenchTimeSeriesChunk.serializer(), timeSeriesJson) == timeSeries)
        check(protoBuf.decodeFromByteArray(BenchUserProfile.serializer(), userProto) == userProfile)
        check(protoBuf.decodeFromByteArray(BenchApiResponse.serializer(), apiProto) == apiResponse)
        check(protoBuf.decodeFromByteArray(BenchTimeSeriesChunk.serializer(), timeSeriesProto) == timeSeries)
        check(BenchUserProfileFlexCoder.decode(callerOwnedUserBytes, callerOwnedUserLimit) == userProfile)
        check(BenchApiResponseFlexCoder.decode(callerOwnedApiBytes, callerOwnedApiLimit) == apiResponse)
        check(BenchTimeSeriesChunkFlexCoder.decode(callerOwnedTimeSeriesBytes, callerOwnedTimeSeriesLimit) == timeSeries)

        updatePayloadSizes()
    }

    private fun assertModuleFlexCodersRegistered() {
        check(FlexCoderRegistry.get(InnerNestedData::class) === InnerNestedDataFlexCoder)
        check(FlexCoderRegistry.get(BenchUserProfile::class) === BenchUserProfileFlexCoder)
        check(FlexCoderRegistry.get(BenchApiResponse::class) === BenchApiResponseFlexCoder)
        check(FlexCoderRegistry.get(BenchTimeSeriesChunk::class) === BenchTimeSeriesChunkFlexCoder)
        check(
            FlexCoderRegistry.getBySerialName<Any>(
                InnerNestedData.serializer().descriptor.serialName,
            ) === InnerNestedDataFlexCoder,
        )
        check(
            FlexCoderRegistry.getBySerialName<Any>(
                BenchUserProfile.serializer().descriptor.serialName,
            ) === BenchUserProfileFlexCoder,
        )
        check(
            FlexCoderRegistry.getBySerialName<Any>(
                BenchApiResponse.serializer().descriptor.serialName,
            ) === BenchApiResponseFlexCoder,
        )
        check(
            FlexCoderRegistry.getBySerialName<Any>(
                BenchTimeSeriesChunk.serializer().descriptor.serialName,
            ) === BenchTimeSeriesChunkFlexCoder,
        )
    }

    /**
     * Explicit generated-coder path with no registry or serializer lookup. Returning the bytes
     * makes the generated JMH harness consume the complete result through its Blackhole.
     */
    @Benchmark
    open fun generatedDirectEncodeOnly(): ByteArray =
        when (caseName) {
            "UserProfile" -> FlexBuffers.encode(BenchUserProfileFlexCoder, userProfile as BenchUserProfile)
            "ApiResponse" -> FlexBuffers.encode(BenchApiResponseFlexCoder, apiResponse as BenchApiResponse)
            "TimeSeries" -> FlexBuffers.encode(BenchTimeSeriesChunkFlexCoder, timeSeries as BenchTimeSeriesChunk)
            else -> error("Unknown case $caseName")
        }

    /**
     * Explicit generated-coder decode path. The decoded object is returned to JMH's
     * Blackhole so result construction cannot be eliminated without adding checksum work
     * to the measured decode itself.
     */
    @Benchmark
    open fun generatedDirectDecodeOnly(): Any =
        when (caseName) {
            "UserProfile" -> FlexBuffers.decode(BenchUserProfileFlexCoder, userFlex)
            "ApiResponse" -> FlexBuffers.decode(BenchApiResponseFlexCoder, apiFlex)
            "TimeSeries" -> FlexBuffers.decode(BenchTimeSeriesChunkFlexCoder, timeSeriesFlex)
            else -> error("Unknown case $caseName")
        }

    @Benchmark
    open fun generatedDirectFullRoundTrip(): Any =
        when (caseName) {
            "UserProfile" -> FlexBuffers.decode(
                BenchUserProfileFlexCoder,
                FlexBuffers.encode(BenchUserProfileFlexCoder, userProfile as BenchUserProfile),
            )
            "ApiResponse" -> FlexBuffers.decode(
                BenchApiResponseFlexCoder,
                FlexBuffers.encode(BenchApiResponseFlexCoder, apiResponse as BenchApiResponse),
            )
            "TimeSeries" -> FlexBuffers.decode(
                BenchTimeSeriesChunkFlexCoder,
                FlexBuffers.encode(BenchTimeSeriesChunkFlexCoder, timeSeries as BenchTimeSeriesChunk),
            )
            else -> error("Unknown case $caseName")
        }

    @Benchmark
    open fun serializerRoutedGeneratedEncodeOnly(): ByteArray =
        when (caseName) {
            "UserProfile" -> FlexBuffers.encode(BenchUserProfile.serializer(), userProfile as BenchUserProfile)
            "ApiResponse" -> FlexBuffers.encode(BenchApiResponse.serializer(), apiResponse as BenchApiResponse)
            "TimeSeries" -> FlexBuffers.encode(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk)
            else -> error("Unknown case $caseName")
        }

    @Benchmark
    open fun serializerRoutedGeneratedDecodeOnly(): Any =
        when (caseName) {
            "UserProfile" -> FlexBuffers.decode(BenchUserProfile.serializer(), userSerializerRoutedFlex)
            "ApiResponse" -> FlexBuffers.decode(BenchApiResponse.serializer(), apiSerializerRoutedFlex)
            "TimeSeries" -> FlexBuffers.decode(BenchTimeSeriesChunk.serializer(), timeSeriesSerializerRoutedFlex)
            else -> error("Unknown case $caseName")
        }

    /** Legacy name retained for report continuity; this is the serializer-routed coder tier. */
    @Benchmark
    open fun flexFullRoundTrip(): Any =
        when (caseName) {
            "UserProfile" -> FlexBuffers.decode(BenchUserProfile.serializer(), FlexBuffers.encode(BenchUserProfile.serializer(), userProfile as BenchUserProfile))
            "ApiResponse" -> FlexBuffers.decode(BenchApiResponse.serializer(), FlexBuffers.encode(BenchApiResponse.serializer(), apiResponse as BenchApiResponse))
            "TimeSeries" -> FlexBuffers.decode(BenchTimeSeriesChunk.serializer(), FlexBuffers.encode(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk))
            else -> error("Unknown case $caseName")
        }

    @Benchmark
    open fun jsonEncodeOnly(): String =
        when (caseName) {
            "UserProfile" -> json.encodeToString(BenchUserProfile.serializer(), userProfile as BenchUserProfile)
            "ApiResponse" -> json.encodeToString(BenchApiResponse.serializer(), apiResponse as BenchApiResponse)
            "TimeSeries" -> json.encodeToString(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk)
            else -> error("Unknown case $caseName")
        }

    @Benchmark
    open fun jsonDecodeOnly(): Any =
        when (caseName) {
            "UserProfile" -> json.decodeFromString(BenchUserProfile.serializer(), userJson)
            "ApiResponse" -> json.decodeFromString(BenchApiResponse.serializer(), apiJson)
            "TimeSeries" -> json.decodeFromString(BenchTimeSeriesChunk.serializer(), timeSeriesJson)
            else -> error("Unknown case $caseName")
        }

    @Benchmark
    open fun jsonFullRoundTrip(): Any =
        when (caseName) {
            "UserProfile" -> json.decodeFromString(BenchUserProfile.serializer(), json.encodeToString(BenchUserProfile.serializer(), userProfile as BenchUserProfile))
            "ApiResponse" -> json.decodeFromString(BenchApiResponse.serializer(), json.encodeToString(BenchApiResponse.serializer(), apiResponse as BenchApiResponse))
            "TimeSeries" -> json.decodeFromString(BenchTimeSeriesChunk.serializer(), json.encodeToString(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk))
            else -> error("Unknown case $caseName")
        }

    @Benchmark
    open fun protoBufEncodeOnly(): ByteArray =
        when (caseName) {
            "UserProfile" -> protoBuf.encodeToByteArray(BenchUserProfile.serializer(), userProfile as BenchUserProfile)
            "ApiResponse" -> protoBuf.encodeToByteArray(BenchApiResponse.serializer(), apiResponse as BenchApiResponse)
            "TimeSeries" -> protoBuf.encodeToByteArray(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk)
            else -> error("Unknown case $caseName")
        }

    @Benchmark
    open fun protoBufDecodeOnly(): Any =
        when (caseName) {
            "UserProfile" -> protoBuf.decodeFromByteArray(BenchUserProfile.serializer(), userProto)
            "ApiResponse" -> protoBuf.decodeFromByteArray(BenchApiResponse.serializer(), apiProto)
            "TimeSeries" -> protoBuf.decodeFromByteArray(BenchTimeSeriesChunk.serializer(), timeSeriesProto)
            else -> error("Unknown case $caseName")
        }

    @Benchmark
    open fun protoBufFullRoundTrip(): Any =
        when (caseName) {
            "UserProfile" -> protoBuf.decodeFromByteArray(
                BenchUserProfile.serializer(),
                protoBuf.encodeToByteArray(BenchUserProfile.serializer(), userProfile as BenchUserProfile),
            )
            "ApiResponse" -> protoBuf.decodeFromByteArray(
                BenchApiResponse.serializer(),
                protoBuf.encodeToByteArray(BenchApiResponse.serializer(), apiResponse as BenchApiResponse),
            )
            "TimeSeries" -> protoBuf.decodeFromByteArray(
                BenchTimeSeriesChunk.serializer(),
                protoBuf.encodeToByteArray(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk),
            )
            else -> error("Unknown case $caseName")
        }

    /**
     * Caller-owned builder encode: no pooled-builder lookup and no final ByteArray copy.
     * The backing array is explicitly consumed because only returning the logical limit would
     * otherwise leave the generated writes vulnerable to dead-code elimination.
    */
    @Benchmark
    open fun callerOwnedGeneratedEncodeOnly(blackhole: Blackhole): Int {
        val builder = callerOwnedBuilder as FlexBuffersBuilder
        return when (caseName) {
            "UserProfile" -> {
                builder.clear()
                BenchUserProfileFlexCoder.encodeRoot(builder, userProfile as BenchUserProfile)
                val limit = builder.finishedLimit()
                blackhole.consume(builder.finishedBytes())
                limit
            }
            "ApiResponse" -> {
                builder.clear()
                BenchApiResponseFlexCoder.encodeRoot(builder, apiResponse as BenchApiResponse)
                val limit = builder.finishedLimit()
                blackhole.consume(builder.finishedBytes())
                limit
            }
            "TimeSeries" -> {
                builder.clear()
                BenchTimeSeriesChunkFlexCoder.encodeRoot(builder, timeSeries as BenchTimeSeriesChunk)
                val limit = builder.finishedLimit()
                blackhole.consume(builder.finishedBytes())
                limit
            }
            else -> error("Unknown case $caseName")
        }
    }

    @Benchmark
    open fun callerOwnedGeneratedDecodeOnly(): Any =
        when (caseName) {
            "UserProfile" -> BenchUserProfileFlexCoder.decode(callerOwnedUserBytes, callerOwnedUserLimit)
            "ApiResponse" -> BenchApiResponseFlexCoder.decode(callerOwnedApiBytes, callerOwnedApiLimit)
            "TimeSeries" -> BenchTimeSeriesChunkFlexCoder.decode(callerOwnedTimeSeriesBytes, callerOwnedTimeSeriesLimit)
            else -> error("Unknown case $caseName")
        }

    @Benchmark
    open fun callerOwnedGeneratedFullRoundTrip(): Any {
        val builder = callerOwnedBuilder as FlexBuffersBuilder
        return when (caseName) {
            "UserProfile" -> {
                builder.clear()
                BenchUserProfileFlexCoder.encodeRoot(builder, userProfile as BenchUserProfile)
                BenchUserProfileFlexCoder.decode(
                    builder.finishedBytes(),
                    builder.finishedLimit(),
                )
            }
            "ApiResponse" -> {
                builder.clear()
                BenchApiResponseFlexCoder.encodeRoot(builder, apiResponse as BenchApiResponse)
                BenchApiResponseFlexCoder.decode(
                    builder.finishedBytes(),
                    builder.finishedLimit(),
                )
            }
            "TimeSeries" -> {
                builder.clear()
                BenchTimeSeriesChunkFlexCoder.encodeRoot(builder, timeSeries as BenchTimeSeriesChunk)
                BenchTimeSeriesChunkFlexCoder.decode(
                    builder.finishedBytes(),
                    builder.finishedLimit(),
                )
            }
            else -> error("Unknown case $caseName")
        }
    }

    /** Legacy partial-field generic scan retained only for historical report continuity. */
    @Benchmark
    open fun flexAccessorOnly(): Long =
        when (caseName) {
            "UserProfile" -> scanUserProfileAccessor(userFlex)
            "ApiResponse" -> scanApiResponseAccessor(apiFlex)
            "TimeSeries" -> scanTimeSeriesAccessor(timeSeriesFlex)
            else -> error("Unknown case $caseName")
        }

    /** Generic string-key accessor doing the same logical field work as the generated accessor. */
    @Benchmark
    open fun genericEquivalentAccessorOnly(): Long =
        when (caseName) {
            "UserProfile" -> scanEquivalentUserProfileAccessor(userFlex)
            "ApiResponse" -> scanEquivalentApiResponseAccessor(apiFlex)
            "TimeSeries" -> scanEquivalentTimeSeriesAccessor(timeSeriesFlex)
            else -> error("Unknown case $caseName")
        }

    /** Typed, KSP-generated zero-copy accessor path with full-decode-equivalent checksums. */
    @Benchmark
    open fun generatedTypedAccessorOnly(): Long =
        when (caseName) {
            "UserProfile" -> scanGeneratedUserProfileAccessor(userFlex)
            "ApiResponse" -> scanGeneratedApiResponseAccessor(apiFlex)
            "TimeSeries" -> scanGeneratedTimeSeriesAccessor(timeSeriesFlex)
            else -> error("Unknown case $caseName")
        }

    /**
     * UNSAFE textual probe retained for result continuity only. It neither validates JSON
     * nor performs work equivalent to structured FlexBuffer field access.
     */
    @Benchmark
    open fun jsonTokenScanOnly(): Int =
        when (caseName) {
            "UserProfile" -> scanJsonTokens(userJson, """"username":"""", """"verified":true""", """"city":"Bengaluru"""")
            "ApiResponse" -> scanJsonTokens(apiJson, """"status":200""", """"items":[""", """"metadata":""")
            "TimeSeries" -> scanJsonTokens(timeSeriesJson, """"seriesId":"""", """"values":[""", """"timestamps":[""")
            else -> error("Unknown case $caseName")
        }

    private fun updatePayloadSizes() {
        when (caseName) {
            "UserProfile" -> {
                generatedDirectPayloadBytes = userFlex.size
                serializerRoutedPayloadBytes = userSerializerRoutedFlex.size
                jsonUtf8PayloadBytes = userJson.encodeToByteArray().size
                protoBufPayloadBytes = userProto.size
                callerOwnedPayloadBytes = callerOwnedUserLimit
            }
            "ApiResponse" -> {
                generatedDirectPayloadBytes = apiFlex.size
                serializerRoutedPayloadBytes = apiSerializerRoutedFlex.size
                jsonUtf8PayloadBytes = apiJson.encodeToByteArray().size
                protoBufPayloadBytes = apiProto.size
                callerOwnedPayloadBytes = callerOwnedApiLimit
            }
            "TimeSeries" -> {
                generatedDirectPayloadBytes = timeSeriesFlex.size
                serializerRoutedPayloadBytes = timeSeriesSerializerRoutedFlex.size
                jsonUtf8PayloadBytes = timeSeriesJson.encodeToByteArray().size
                protoBufPayloadBytes = timeSeriesProto.size
                callerOwnedPayloadBytes = callerOwnedTimeSeriesLimit
            }
            else -> error("Unknown case $caseName")
        }
        check(generatedDirectPayloadBytes == serializerRoutedPayloadBytes)
        check(generatedDirectPayloadBytes == callerOwnedPayloadBytes)
    }

    private fun ByteArray.contentEqualsPrefix(other: ByteArray, otherLength: Int): Boolean {
        if (size != otherLength) return false
        for (i in indices) if (this[i] != other[i]) return false
        return true
    }

    private fun scanGeneratedUserProfileAccessor(bytes: ByteArray): Long {
        val value = bytes.asBenchUserProfile()
        return value.id + value.username.length + value.displayName.length + value.email.length +
            value.bio.length + value.avatarUrl.length + value.followerCount + value.followingCount +
            value.postCount + value.createdAtEpochMs + value.tags.size + value.settings.size +
            value.address.city.length + if (value.verified) 1 else 0
    }

    private fun scanGeneratedApiResponseAccessor(bytes: ByteArray): Long {
        val value = bytes.asBenchApiResponse()
        val items = value.items
        var checksum = value.status.toLong() + value.page + value.pageSize + value.totalItems +
            value.totalPages + value.metadata.size
        for (i in 0 until items.size) {
            val item = items[i]
            checksum += item.id + item.name.length + item.priceInCents + item.reviewCount
        }
        return checksum
    }

    private fun scanGeneratedTimeSeriesAccessor(bytes: ByteArray): Long {
        val value = bytes.asBenchTimeSeriesChunk()
        var checksum = value.startEpochMs + value.intervalMs + value.count + value.seriesId.length
        val values = value.values
        for (i in 0 until values.size) checksum += values[i].toLong()
        val timestampValues = value.timestamps
        var timestamps = 0L
        for (i in 0 until timestampValues.size) timestamps = timestamps xor timestampValues[i]
        return checksum + timestamps
    }

    private fun scanEquivalentUserProfileAccessor(bytes: ByteArray): Long {
        val map = bytes.toFlexMap()
        val address = map["address"].toMap()
        return map.getLong("id") +
            map.getString("username").length +
            map.getString("displayName").length +
            map.getString("email").length +
            map.getString("bio").length +
            map.getString("avatarUrl").length +
            map.getLong("followerCount") +
            map.getLong("followingCount") +
            map.getLong("postCount") +
            map.getLong("createdAtEpochMs") +
            map.getVector(map.indexOf("tags")).size +
            map.getMap(map.indexOf("settings")).size +
            address.getString("city").length +
            if (map.getBoolean("verified")) 1 else 0
    }

    private fun scanEquivalentApiResponseAccessor(bytes: ByteArray): Long {
        val map = bytes.toFlexMap()
        val items = map.getVector(map.indexOf("items"))
        var checksum = map.getLong("status") + map.getLong("page") + map.getLong("pageSize") +
            map.getLong("totalItems") + map.getLong("totalPages") +
            map.getMap(map.indexOf("metadata")).size
        for (i in 0 until items.size) {
            val item = items[i].toMap()
            checksum += item.getLong("id") + item.getString("name").length +
                item.getLong("priceInCents") + item.getLong("reviewCount")
        }
        return checksum
    }

    private fun scanEquivalentTimeSeriesAccessor(bytes: ByteArray): Long {
        val map = bytes.toFlexMap()
        val values = map.getVector(map.indexOf("values"))
        val timestamps = map.getVector(map.indexOf("timestamps"))
        var checksum = map.getLong("startEpochMs") + map.getLong("intervalMs") +
            map.getLong("count") + map.getString("seriesId").length
        for (i in 0 until values.size) checksum += values.readDouble(i).toLong()
        var timestampChecksum = 0L
        for (i in 0 until timestamps.size) timestampChecksum = timestampChecksum xor timestamps.readLong(i)
        return checksum + timestampChecksum
    }

    private fun scanUserProfileAccessor(bytes: ByteArray): Long {
        val map = bytes.toFlexMap()
        val address = map["address"].toMap()
        return map.getLong("id") +
            map.getStringByteLength("username") +
            map.getStringByteLength("displayName") +
            map.getStringByteLength("email") +
            map.getStringByteLength("bio") +
            map.getStringByteLength("avatarUrl") +
            map.getLong("followerCount") +
            map.getLong("followingCount") +
            map.getLong("postCount") +
            map.getLong("createdAtEpochMs") +
            map.getVector(map.indexOf("tags")).size +
            map.getMap(map.indexOf("settings")).size +
            address.getStringByteLength("city") +
            if (map.getBoolean("verified")) 1 else 0
    }

    private fun scanApiResponseAccessor(bytes: ByteArray): Long {
        val map = bytes.toFlexMap()
        val items = map.getVector(map.indexOf("items"))
        var sum = map.getLong("status") + map.getLong("page") + map.getLong("totalItems") + items.size
        if (items.size > 0) {
            val first = items[0].toMap()
            val last = items[items.size - 1].toMap()
            sum += first.getLong("id") + first.getStringByteLength("name") + first.getLong("priceInCents")
            sum += last.getLong("id") + last.getStringByteLength("description") + last.getLong("reviewCount")
        }
        return sum
    }

    private fun scanTimeSeriesAccessor(bytes: ByteArray): Long {
        val map = bytes.toFlexMap()
        val values = map.getVector(map.indexOf("values"))
        val timestamps = map.getVector(map.indexOf("timestamps"))
        var checksum = map.getLong("startEpochMs") + map.getLong("intervalMs") + map.getLong("count")
        checksum += (map.getDouble("min") + map.getDouble("max") + map.getDouble("mean")).toLong()
        var i = 0
        while (i < values.size) {
            checksum += values.readDouble(i).toLong()
            checksum += timestamps.readLong(i)
            i += 17
        }
        return checksum
    }

    private fun scanJsonTokens(json: String, vararg tokens: String): Int {
        var sum = 0
        for (token in tokens) {
            val index = json.indexOf(token)
            if (index >= 0) sum += index + token.length
        }
        return sum
    }

    private fun checksumUser(value: Any): Long {
        value as BenchUserProfile
        return (
        value.id + value.username.length + value.displayName.length + value.email.length +
            value.bio.length + value.avatarUrl.length + value.followerCount + value.followingCount +
            value.postCount + value.createdAtEpochMs + value.tags.size + value.settings.size +
            value.address.city.length + if (value.verified) 1 else 0
        )
    }

    private fun checksumApi(value: Any): Long {
        value as BenchApiResponse
        return value.status.toLong() + value.page + value.pageSize + value.totalItems + value.totalPages +
            value.items.sumOf { it.id + it.name.length + it.priceInCents + it.reviewCount } +
            value.metadata.size
    }

    private fun checksumTimeSeries(value: Any): Long {
        value as BenchTimeSeriesChunk
        return value.startEpochMs + value.intervalMs + value.count +
            value.values.sumOf { it.toLong() } +
            value.timestamps.fold(0L) { acc, next -> acc xor next } +
            value.seriesId.length
    }
}

/**
 * Raw kotlinx.serialization-driven FlexBuffer tier. It has a separate JMH state because both
 * FlexBuffers and FlexEncoderV2/FlexDecoderV2 consult the process-global coder registry. The
 * configured forked run gives every benchmark its own JVM; setup clears and verifies the registry
 * before constructing any raw payload.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class RawFlexBufferJmhBenchmark {
    @Param("UserProfile", "ApiResponse", "TimeSeries")
    var caseName: String = "UserProfile"

    private val json = Json { encodeDefaults = true }
    private val protoBuf = ProtoBuf { encodeDefaults = true }

    private lateinit var userProfile: Any
    private lateinit var apiResponse: Any
    private lateinit var timeSeries: Any

    private lateinit var userRawFlex: ByteArray
    private lateinit var apiRawFlex: ByteArray
    private lateinit var timeSeriesRawFlex: ByteArray

    @JvmField var generatedDirectPayloadBytes: Int = 0
    @JvmField var rawKotlinxFlexPayloadBytes: Int = 0
    @JvmField var jsonUtf8PayloadBytes: Int = 0
    @JvmField var protoBufPayloadBytes: Int = 0
    @JvmField var callerOwnedPayloadBytes: Int = 0

    @Setup
    open fun setup() {
        FlexCoderRegistry.clear()
        check(FlexCoderRegistry.getBySerialName<Any>(BenchUserProfile.serializer().descriptor.serialName) == null)
        check(FlexCoderRegistry.getBySerialName<Any>(BenchApiResponse.serializer().descriptor.serialName) == null)
        check(FlexCoderRegistry.getBySerialName<Any>(BenchTimeSeriesChunk.serializer().descriptor.serialName) == null)

        userProfile = BenchmarkData.userProfile()
        apiResponse = BenchmarkData.apiResponse()
        timeSeries = BenchmarkData.timeSeriesChunk()

        // Empty registry makes these public serializer calls reliably take FlexEncoderV2.
        userRawFlex = FlexBuffers.encode(BenchUserProfile.serializer(), userProfile as BenchUserProfile)
        apiRawFlex = FlexBuffers.encode(BenchApiResponse.serializer(), apiResponse as BenchApiResponse)
        timeSeriesRawFlex = FlexBuffers.encode(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk)

        check(FlexBuffers.decode(BenchUserProfile.serializer(), userRawFlex) == userProfile)
        check(FlexBuffers.decode(BenchApiResponse.serializer(), apiRawFlex) == apiResponse)
        check(FlexBuffers.decode(BenchTimeSeriesChunk.serializer(), timeSeriesRawFlex) == timeSeries)

        val userDirect = FlexBuffers.encode(BenchUserProfileFlexCoder, userProfile as BenchUserProfile)
        val apiDirect = FlexBuffers.encode(BenchApiResponseFlexCoder, apiResponse as BenchApiResponse)
        val timeSeriesDirect = FlexBuffers.encode(BenchTimeSeriesChunkFlexCoder, timeSeries as BenchTimeSeriesChunk)
        val userProto = protoBuf.encodeToByteArray(BenchUserProfile.serializer(), userProfile as BenchUserProfile)
        val apiProto = protoBuf.encodeToByteArray(BenchApiResponse.serializer(), apiResponse as BenchApiResponse)
        val timeSeriesProto = protoBuf.encodeToByteArray(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk)

        when (caseName) {
            "UserProfile" -> {
                generatedDirectPayloadBytes = userDirect.size
                rawKotlinxFlexPayloadBytes = userRawFlex.size
                jsonUtf8PayloadBytes = json.encodeToString(BenchUserProfile.serializer(), userProfile as BenchUserProfile).encodeToByteArray().size
                protoBufPayloadBytes = userProto.size
                callerOwnedPayloadBytes = FlexBuffersBuilder(16 * 1024, FlexBuffersBuilder.SHARE_KEYS).run {
                    BenchUserProfileFlexCoder.encodeRoot(this, userProfile as BenchUserProfile)
                    finishedLimit()
                }
            }
            "ApiResponse" -> {
                generatedDirectPayloadBytes = apiDirect.size
                rawKotlinxFlexPayloadBytes = apiRawFlex.size
                jsonUtf8PayloadBytes = json.encodeToString(BenchApiResponse.serializer(), apiResponse as BenchApiResponse).encodeToByteArray().size
                protoBufPayloadBytes = apiProto.size
                callerOwnedPayloadBytes = FlexBuffersBuilder(16 * 1024, FlexBuffersBuilder.SHARE_KEYS).run {
                    BenchApiResponseFlexCoder.encodeRoot(this, apiResponse as BenchApiResponse)
                    finishedLimit()
                }
            }
            "TimeSeries" -> {
                generatedDirectPayloadBytes = timeSeriesDirect.size
                rawKotlinxFlexPayloadBytes = timeSeriesRawFlex.size
                jsonUtf8PayloadBytes = json.encodeToString(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk).encodeToByteArray().size
                protoBufPayloadBytes = timeSeriesProto.size
                callerOwnedPayloadBytes = FlexBuffersBuilder(16 * 1024, FlexBuffersBuilder.SHARE_KEYS).run {
                    BenchTimeSeriesChunkFlexCoder.encodeRoot(this, timeSeries as BenchTimeSeriesChunk)
                    finishedLimit()
                }
            }
            else -> error("Unknown case $caseName")
        }

        check(generatedDirectPayloadBytes == callerOwnedPayloadBytes)
        println(
            "WIRE_SIZE|$caseName|direct=$generatedDirectPayloadBytes|raw=$rawKotlinxFlexPayloadBytes|" +
                "jsonUtf8=$jsonUtf8PayloadBytes|protobuf=$protoBufPayloadBytes|callerOwned=$callerOwnedPayloadBytes",
        )
    }

    @Benchmark
    open fun rawKotlinxFlexEncodeOnly(): ByteArray =
        when (caseName) {
            "UserProfile" -> FlexBuffers.encode(BenchUserProfile.serializer(), userProfile as BenchUserProfile)
            "ApiResponse" -> FlexBuffers.encode(BenchApiResponse.serializer(), apiResponse as BenchApiResponse)
            "TimeSeries" -> FlexBuffers.encode(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk)
            else -> error("Unknown case $caseName")
        }

    @Benchmark
    open fun rawKotlinxFlexDecodeOnly(): Any =
        when (caseName) {
            "UserProfile" -> FlexBuffers.decode(BenchUserProfile.serializer(), userRawFlex)
            "ApiResponse" -> FlexBuffers.decode(BenchApiResponse.serializer(), apiRawFlex)
            "TimeSeries" -> FlexBuffers.decode(BenchTimeSeriesChunk.serializer(), timeSeriesRawFlex)
            else -> error("Unknown case $caseName")
        }

    @Benchmark
    open fun rawKotlinxFlexFullRoundTrip(): Any =
        when (caseName) {
            "UserProfile" -> FlexBuffers.decode(
                BenchUserProfile.serializer(),
                FlexBuffers.encode(BenchUserProfile.serializer(), userProfile as BenchUserProfile),
            )
            "ApiResponse" -> FlexBuffers.decode(
                BenchApiResponse.serializer(),
                FlexBuffers.encode(BenchApiResponse.serializer(), apiResponse as BenchApiResponse),
            )
            "TimeSeries" -> FlexBuffers.decode(
                BenchTimeSeriesChunk.serializer(),
                FlexBuffers.encode(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk),
            )
            else -> error("Unknown case $caseName")
        }

    private fun checksumUser(value: Any): Long {
        value as BenchUserProfile
        return (
        value.id + value.username.length + value.displayName.length + value.email.length +
            value.bio.length + value.avatarUrl.length + value.followerCount + value.followingCount +
            value.postCount + value.createdAtEpochMs + value.tags.size + value.settings.size +
            value.address.city.length + if (value.verified) 1 else 0
        )
    }

    private fun checksumApi(value: Any): Long {
        value as BenchApiResponse
        return value.status.toLong() + value.page + value.pageSize + value.totalItems + value.totalPages +
            value.items.sumOf { it.id + it.name.length + it.priceInCents + it.reviewCount } +
            value.metadata.size
    }

    private fun checksumTimeSeries(value: Any): Long {
        value as BenchTimeSeriesChunk
        return value.startEpochMs + value.intervalMs + value.count +
            value.values.sumOf { it.toLong() } +
            value.timestamps.fold(0L) { acc, next -> acc xor next } +
            value.seriesId.length
    }
}
