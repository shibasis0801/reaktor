package dev.shibasis.reaktor.service

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
enum class FleetKind(val client: Boolean = false) {
    Worker, DurableObject, Queue, Server, Database, PubSub, Storage, Web(true), Desktop(true), Android(true), iOS(true), Machine
}

@Serializable
enum class FleetStatus { Up, Degraded, Down }

@Serializable
enum class PlaceSource { Request, Trace, Metadata, Egress, Intake, Cluster, Endpoint, Reply }

@Serializable
data class FleetPlace(
    val provider: String = "",
    val region: String = "",
    val zone: String = "",
    val colo: String = "",
    val country: String = "",
    val city: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("located_by") val locatedBy: List<PlaceSource> = emptyList(),
) {
    val pinned get() = latitude != null && longitude != null

    infix fun or(fallback: FleetPlace): FleetPlace {
        val point = if (pinned) this else fallback
        return FleetPlace(provider.ifEmpty { fallback.provider }, region.ifEmpty { fallback.region }, zone.ifEmpty { fallback.zone },
            colo.ifEmpty { fallback.colo }, country.ifEmpty { fallback.country }, city.ifEmpty { fallback.city },
            point.latitude, point.longitude, (locatedBy + fallback.locatedBy).distinct())
    }
}

@Serializable
data class FleetReport(
    @SerialName("reported_ms") val reportedMillis: Long,
    val system: String,
    val component: String,
    val kind: FleetKind,
    val instance: String = "",
    val environment: String = "",
    val build: String = "",
    val provider: String = "",
    val region: String = "",
    val colo: String = "",
    val country: String = "",
    @SerialName("started_ms") val startedMillis: Long = 0,
    val status: FleetStatus = FleetStatus.Up,
    val observer: String = "",
    val samples: Int = 0,
    val timings: Map<String, Double> = emptyMap(),
    val zone: String = "",
    val city: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("located_by") val locatedBy: List<PlaceSource> = emptyList(),
    @SerialName("user_id") val user: String = "",
    val device: String = "",
    val os: String = "",
) {
    val place get() = FleetPlace(provider, region, zone, colo, country, city, latitude, longitude, locatedBy)

    fun at(place: FleetPlace) = copy(provider = place.provider, region = place.region, zone = place.zone, colo = place.colo,
        country = place.country, city = place.city, latitude = place.latitude, longitude = place.longitude, locatedBy = place.locatedBy)

    fun validated(): FleetReport = also {
        require(system.matches(Name) && component.matches(Name) && (observer.isEmpty() || observer.matches(Name)))
        require(listOf(instance, environment, build, provider, region, zone).all { it.length <= 128 && it.matches(Text) })
        require(colo.isEmpty() || colo.matches(Colo))
        require(country.isEmpty() || country.matches(Country))
        require(city.length <= 64 && city.none { it.isISOControl() || it in "<>\"\\" })
        require((latitude == null) == (longitude == null) && (latitude ?: 0.0) in -90.0..90.0 && (longitude ?: 0.0) in -180.0..180.0)
        require(!kind.client || provider.isEmpty() && region.isEmpty() && zone.isEmpty() && city.isEmpty() && latitude == null &&
            (country.isEmpty() || colo.isEmpty()))
        require(user.length <= 128 && user.matches(Text) && listOf(device, os).all { it.length <= 64 && it.all(ClientDescription::allowed) })
        require(kind.client || user.isEmpty() && device.isEmpty() && os.isEmpty())
        require(reportedMillis > 0 && startedMillis in 0..reportedMillis + 60_000 && samples >= 0)
        require(timings.size <= 16 && timings.all { (key, value) -> key.matches(Timing) && value.isFinite() && value >= 0 })
    }

    fun measured(name: String, durations: List<Double>): FleetReport {
        val sorted = durations.filter { it.isFinite() && it >= 0 }.sorted()
        if (sorted.isEmpty()) return this
        fun percentile(quantile: Double) = sorted[((sorted.size - 1) * quantile).roundToInt()]
        return copy(samples = samples + sorted.size, timings = timings + mapOf(
            "${name}_p50_ms" to percentile(0.5), "${name}_p95_ms" to percentile(0.95), "${name}_max_ms" to sorted.last()))
    }

    companion object {
        const val Interval = 60_000L
        private val Name = Regex("[a-z][a-z0-9.-]{0,63}")
        private val Text = Regex("[A-Za-z0-9_.:/@ -]*")
        private val Colo = Regex("[A-Z]{3}")
        private val Country = Regex("[A-Z]{2}")
        private val Timing = Regex("[a-z][a-z0-9_]{0,47}_ms")
    }
}

class FleetTimings(private val capacity: Int = 4096) {
    private val lock = SynchronizedObject()
    private var durations = ArrayList<Double>()

    fun record(millis: Double) {
        synchronized(lock) { if (durations.size < capacity) durations.add(millis) }
    }

    fun drain(): List<Double> = synchronized(lock) { durations.also { durations = ArrayList() } }
}
