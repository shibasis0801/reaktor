@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shibasis.reaktor.flexbuffer.bench

import dev.shibasis.reaktor.flexbuffer.BenchApiResponse
import dev.shibasis.reaktor.flexbuffer.BenchApiResponseFlexCoder
import dev.shibasis.reaktor.flexbuffer.BenchTimeSeriesChunk
import dev.shibasis.reaktor.flexbuffer.BenchTimeSeriesChunkFlexCoder
import dev.shibasis.reaktor.flexbuffer.BenchUserProfile
import dev.shibasis.reaktor.flexbuffer.BenchUserProfileFlexCoder
import dev.shibasis.reaktor.flexbuffer.BenchmarkData
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import dev.shibasis.reaktor.flexbuffer.core.MaterializedDoubleList
import dev.shibasis.reaktor.flexbuffer.core.MaterializedIntList
import dev.shibasis.reaktor.flexbuffer.core.MaterializedLongList
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import java.util.concurrent.TimeUnit

/**
 * Measures generated encoding from ordinary Kotlin collections against encoding the same model
 * after a generated decode. The latter is the read/modify/write shape used by applications, and
 * deliberately keeps generated primitive-list materializations as the encoder input.
 *
 * State fields and benchmark descriptors intentionally avoid the Java-25 production builder and
 * coder types so kotlinx-benchmark's Java-21 reflection generator can inspect this class.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class MaterializedReencodeJmhBenchmark {
    @Param("UserProfile", "ApiResponse", "TimeSeries")
    var caseName: String = "UserProfile"

    private lateinit var originalUser: Any
    private lateinit var originalApi: Any
    private lateinit var originalTimeSeries: Any

    private lateinit var decodedUser: Any
    private lateinit var decodedApi: Any
    private lateinit var decodedTimeSeries: Any

    @Setup
    open fun setup() {
        originalUser = BenchmarkData.userProfile()
        originalApi = BenchmarkData.apiResponse()
        originalTimeSeries = BenchmarkData.timeSeriesChunk()

        val userBytes = FlexBuffers.encode(BenchUserProfileFlexCoder, originalUser as BenchUserProfile)
        val apiBytes = FlexBuffers.encode(BenchApiResponseFlexCoder, originalApi as BenchApiResponse)
        val timeSeriesBytes = FlexBuffers.encode(
            BenchTimeSeriesChunkFlexCoder,
            originalTimeSeries as BenchTimeSeriesChunk,
        )

        decodedUser = FlexBuffers.decode(BenchUserProfileFlexCoder, userBytes)
        decodedApi = FlexBuffers.decode(BenchApiResponseFlexCoder, apiBytes)
        decodedTimeSeries = FlexBuffers.decode(BenchTimeSeriesChunkFlexCoder, timeSeriesBytes)

        // These guards keep this benchmark tied to the primitive-backed decode/re-encode path.
        check((decodedApi as BenchApiResponse).items.all { it.categoryIds is MaterializedIntList })
        check((decodedTimeSeries as BenchTimeSeriesChunk).values is MaterializedDoubleList)
        check((decodedTimeSeries as BenchTimeSeriesChunk).timestamps is MaterializedLongList)

        verifyReencodeCorrectness()
    }

    /** Generated encode with the ordinary List implementations produced by BenchmarkData. */
    @Benchmark
    open fun originalGeneratedEncodeOnly(): ByteArray =
        when (caseName) {
            "UserProfile" -> FlexBuffers.encode(
                BenchUserProfileFlexCoder,
                originalUser as BenchUserProfile,
            )
            "ApiResponse" -> FlexBuffers.encode(
                BenchApiResponseFlexCoder,
                originalApi as BenchApiResponse,
            )
            "TimeSeries" -> FlexBuffers.encode(
                BenchTimeSeriesChunkFlexCoder,
                originalTimeSeries as BenchTimeSeriesChunk,
            )
            else -> error("Unknown case $caseName")
        }

    /** Generated re-encode with the fully materialized object returned by generated decode. */
    @Benchmark
    open fun decodedGeneratedReencodeOnly(): ByteArray =
        when (caseName) {
            "UserProfile" -> FlexBuffers.encode(
                BenchUserProfileFlexCoder,
                decodedUser as BenchUserProfile,
            )
            "ApiResponse" -> FlexBuffers.encode(
                BenchApiResponseFlexCoder,
                decodedApi as BenchApiResponse,
            )
            "TimeSeries" -> FlexBuffers.encode(
                BenchTimeSeriesChunkFlexCoder,
                decodedTimeSeries as BenchTimeSeriesChunk,
            )
            else -> error("Unknown case $caseName")
        }

    private fun verifyReencodeCorrectness() {
        val userReencoded = FlexBuffers.encode(BenchUserProfileFlexCoder, decodedUser as BenchUserProfile)
        val apiReencoded = FlexBuffers.encode(BenchApiResponseFlexCoder, decodedApi as BenchApiResponse)
        val timeSeriesReencoded = FlexBuffers.encode(
            BenchTimeSeriesChunkFlexCoder,
            decodedTimeSeries as BenchTimeSeriesChunk,
        )

        val userRoundTrip = FlexBuffers.decode(BenchUserProfileFlexCoder, userReencoded)
        val apiRoundTrip = FlexBuffers.decode(BenchApiResponseFlexCoder, apiReencoded)
        val timeSeriesRoundTrip = FlexBuffers.decode(BenchTimeSeriesChunkFlexCoder, timeSeriesReencoded)

        check(userRoundTrip == originalUser)
        check(apiRoundTrip == originalApi)
        check(timeSeriesRoundTrip == originalTimeSeries)

    }

}
