@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shibasis.reaktor.flexbuffer.bench

import dev.shibasis.reaktor.core.registerGeneratedFlexCoders
import dev.shibasis.reaktor.flexbuffer.BenchApiResponse
import dev.shibasis.reaktor.flexbuffer.BenchTimeSeriesChunk
import dev.shibasis.reaktor.flexbuffer.BenchUserProfile
import dev.shibasis.reaktor.flexbuffer.BenchmarkData
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import dev.shibasis.reaktor.flexbuffer.core.toFlexMap
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class FlexBufferJmhBenchmark {
    @Param("UserProfile", "ApiResponse", "TimeSeries")
    var caseName: String = "UserProfile"

    private val json = Json { encodeDefaults = true }

    private lateinit var userProfile: Any
    private lateinit var apiResponse: Any
    private lateinit var timeSeries: Any

    private lateinit var userFlex: ByteArray
    private lateinit var apiFlex: ByteArray
    private lateinit var timeSeriesFlex: ByteArray

    private lateinit var userJson: String
    private lateinit var apiJson: String
    private lateinit var timeSeriesJson: String

    @Setup
    open fun setup() {
        registerGeneratedFlexCoders()

        userProfile = BenchmarkData.userProfile()
        apiResponse = BenchmarkData.apiResponse()
        timeSeries = BenchmarkData.timeSeriesChunk()

        userFlex = FlexBuffers.encode(BenchUserProfile.serializer(), userProfile as BenchUserProfile)
        apiFlex = FlexBuffers.encode(BenchApiResponse.serializer(), apiResponse as BenchApiResponse)
        timeSeriesFlex = FlexBuffers.encode(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk)

        userJson = json.encodeToString(BenchUserProfile.serializer(), userProfile as BenchUserProfile)
        apiJson = json.encodeToString(BenchApiResponse.serializer(), apiResponse as BenchApiResponse)
        timeSeriesJson = json.encodeToString(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk)
    }

    @Benchmark
    open fun flexFullRoundTrip(): Long =
        when (caseName) {
            "UserProfile" -> checksumUser(FlexBuffers.decode(BenchUserProfile.serializer(), FlexBuffers.encode(BenchUserProfile.serializer(), userProfile as BenchUserProfile)))
            "ApiResponse" -> checksumApi(FlexBuffers.decode(BenchApiResponse.serializer(), FlexBuffers.encode(BenchApiResponse.serializer(), apiResponse as BenchApiResponse)))
            "TimeSeries" -> checksumTimeSeries(FlexBuffers.decode(BenchTimeSeriesChunk.serializer(), FlexBuffers.encode(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk)))
            else -> error("Unknown case $caseName")
        }

    @Benchmark
    open fun jsonFullRoundTrip(): Long =
        when (caseName) {
            "UserProfile" -> checksumUser(json.decodeFromString(BenchUserProfile.serializer(), json.encodeToString(BenchUserProfile.serializer(), userProfile as BenchUserProfile)))
            "ApiResponse" -> checksumApi(json.decodeFromString(BenchApiResponse.serializer(), json.encodeToString(BenchApiResponse.serializer(), apiResponse as BenchApiResponse)))
            "TimeSeries" -> checksumTimeSeries(json.decodeFromString(BenchTimeSeriesChunk.serializer(), json.encodeToString(BenchTimeSeriesChunk.serializer(), timeSeries as BenchTimeSeriesChunk)))
            else -> error("Unknown case $caseName")
        }

    @Benchmark
    open fun flexAccessorOnly(): Long =
        when (caseName) {
            "UserProfile" -> scanUserProfileAccessor(userFlex)
            "ApiResponse" -> scanApiResponseAccessor(apiFlex)
            "TimeSeries" -> scanTimeSeriesAccessor(timeSeriesFlex)
            else -> error("Unknown case $caseName")
        }

    @Benchmark
    open fun jsonTokenScanOnly(): Int =
        when (caseName) {
            "UserProfile" -> scanJsonTokens(userJson, """"username":"""", """"verified":true""", """"city":"Bengaluru"""")
            "ApiResponse" -> scanJsonTokens(apiJson, """"status":200""", """"items":[""", """"metadata":""")
            "TimeSeries" -> scanJsonTokens(timeSeriesJson, """"seriesId":"""", """"values":[""", """"timestamps":[""")
            else -> error("Unknown case $caseName")
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
