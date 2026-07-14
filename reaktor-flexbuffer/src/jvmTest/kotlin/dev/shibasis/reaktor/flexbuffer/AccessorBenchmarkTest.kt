@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.flexbuffer.generated.ReaktorFlexbufferCoders
import dev.shibasis.reaktor.flexbuffer.BenchUserProfileAccessor
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import dev.shibasis.reaktor.flexbuffer.core.FlexCoderRegistry
import dev.shibasis.reaktor.flexbuffer.core.toFlexMap
import dev.shibasis.reaktor.flexbuffer.flatbuffers.ArrayReadBuffer
import dev.shibasis.reaktor.flexbuffer.flatbuffers.getRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

class AccessorBenchmarkTest {

    private val warmup = 1000
    private val iterations = 10000

    private inline fun benchUs(label: String, crossinline block: () -> Any): Double {
        repeat(warmup) { block() }
        var result: Any? = null
        val elapsed = measureTime { repeat(iterations) { result = block() } }
        val usPerOp = elapsed.inWholeMicroseconds.toDouble() / iterations
        println("  %-45s %6.2f us/op".format(label, usPerOp))
        return usPerOp
    }

    @Test
    fun accessorCorrectnessTest() {
        ReaktorFlexbufferCoders.register()
        val profile = BenchmarkData.userProfile()
        val bytes = FlexBuffers.encode(profile)
        val map = bytes.toFlexMap()
        val accessor = BenchUserProfileAccessor(map)

        assertEquals(profile.id, accessor.id)
        assertEquals(profile.username, accessor.username)
        assertEquals(profile.displayName, accessor.displayName)
        assertEquals(profile.email, accessor.email)
        assertEquals(profile.bio, accessor.bio)
        assertEquals(profile.avatarUrl, accessor.avatarUrl)
        assertEquals(profile.followerCount, accessor.followerCount)
        assertEquals(profile.followingCount, accessor.followingCount)
        assertEquals(profile.postCount, accessor.postCount)
        assertEquals(profile.verified, accessor.verified)
        assertEquals(profile.createdAtEpochMs, accessor.createdAtEpochMs)
        assertEquals(profile.tags.size, accessor.tags.size)
        for (i in profile.tags.indices) assertEquals(profile.tags[i], accessor.tags[i])
        assertEquals(profile.address.city, accessor.address.city)
        assertEquals(profile.address.street, accessor.address.street)
        assertEquals(profile.address.country, accessor.address.country)

        val roundTripped = accessor.toDataClass()
        assertEquals(profile.id, roundTripped.id)
        assertEquals(profile.username, roundTripped.username)
        assertEquals(profile.tags, roundTripped.tags)
        assertEquals(profile.settings, roundTripped.settings)
        assertEquals(profile.address, roundTripped.address)

        println("Accessor correctness: PASS")
    }

    @Test
    fun accessorVsDecodeBenchmark() {
        ReaktorFlexbufferCoders.register()
        val profile = BenchmarkData.userProfile()
        val bytes = FlexBuffers.encode(profile)

        println("\n=== UserProfile Accessor vs Decode Benchmark ===")
        println("  Warmup: $warmup, Iterations: $iterations")
        println()

        // Tier 1: Accessor — read all fields, zero-copy
        val accessorRead = benchUs("Accessor (read all fields)") {
            val map = bytes.toFlexMap()
            val a = BenchUserProfileAccessor(map)
            // Read every field to force materialization
            a.id + a.followerCount + a.followingCount + a.postCount +
                a.username.length + a.displayName.length + a.email.length +
                a.bio.length + a.avatarUrl.length +
                a.createdAtEpochMs + (if (a.verified) 1 else 0) +
                a.tags.size + a.settings.size +
                a.address.city.length + a.address.street.length
        }

        // Tier 2: Accessor toDataClass — allocates data class from index-based reads
        val accessorToData = benchUs("Accessor toDataClass()") {
            val map = bytes.toFlexMap()
            BenchUserProfileAccessor(map).toDataClass()
        }

        // Tier 3: FlexCoder decode — index-based, allocates data class
        val flexDecode = benchUs("FlexCoder decode (index-based)") {
            FlexBuffers.decode<BenchUserProfile>(bytes)
        }

        println()
        println("  --- Speedup vs FlexCoder decode ---")
        println("  Accessor read:       %.1fx faster".format(flexDecode / accessorRead))
        println("  Accessor toData:     %.1fx faster".format(flexDecode / accessorToData))
        println()
        println("  C++ decode reference: ~0.33 us key-based, ~0.04 us index-based (UserProfile)")
    }

    @Test
    fun encodeBenchmark() {
        ReaktorFlexbufferCoders.register()
        val profile = BenchmarkData.userProfile()
        val chatThread = BenchmarkData.chatThread()
        val apiResponse = BenchmarkData.apiResponse()
        val eventLog = BenchmarkData.eventLog()
        val timeSeries = BenchmarkData.timeSeriesChunk()
        val config = BenchmarkData.configSnapshot()

        println("\n=== Encode Benchmark (FlexCoder, pooled builder) ===")
        println("  Warmup: $warmup, Iterations: $iterations")
        println()

        benchUs("UserProfile encode (14 fields, nested)") { FlexBuffers.encode(profile) }
        benchUs("ChatThread encode (15 msgs, nested)") { FlexBuffers.encode(chatThread) }
        benchUs("ApiResponse encode (20 products)") { FlexBuffers.encode(apiResponse) }
        benchUs("EventLog encode (string+double maps)") { FlexBuffers.encode(eventLog) }
        benchUs("TimeSeries encode (256d + 256L)") { FlexBuffers.encode(timeSeries) }
        benchUs("ConfigSnapshot encode (nested maps)") { FlexBuffers.encode(config) }
    }
}
