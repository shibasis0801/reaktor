package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.service.FleetPlace
import dev.shibasis.reaktor.service.PlaceSource
import kotlinx.coroutines.await
import kotlin.js.Promise

object CloudflarePlace {
    val inWorkers: Boolean = js("typeof navigator !== 'undefined' && navigator.userAgent === 'Cloudflare-Workers'") as Boolean

    fun serving(request: dynamic): FleetPlace =
        code(request?.cf?.colo, Colo).let { colo -> if (colo.isEmpty()) FleetPlace() else edge(colo, PlaceSource.Request) }

    fun caller(request: dynamic): FleetPlace {
        val cf = request?.cf ?: return FleetPlace()
        val latitude = (cf.latitude as? String)?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
        val longitude = (cf.longitude as? String)?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
        val pinned = latitude != null && longitude != null
        return FleetPlace(country = code(cf.country, Country), city = (cf.city as? String).orEmpty().filterNot { it.isISOControl() }.take(64),
            latitude = latitude.takeIf { pinned }, longitude = longitude.takeIf { pinned }, locatedBy = listOf(PlaceSource.Egress))
    }

    suspend fun traced(): FleetPlace = runCatching {
        val response = js("fetch('https://www.cloudflare.com/cdn-cgi/trace', { signal: AbortSignal.timeout(2000) })").unsafeCast<Promise<dynamic>>().await()
        val text = response.text().unsafeCast<Promise<String>>().await()
        val colo = text.lineSequence().firstOrNull { it.startsWith("colo=") }?.substringAfter('=').orEmpty()
        if (colo.matches(Colo)) edge(colo, PlaceSource.Trace) else FleetPlace()
    }.getOrDefault(FleetPlace())

    fun answer(request: dynamic): dynamic = workerResponse(json.encodeToString(FleetPlace.serializer(), caller(request)),
        headers = mapOf("content-type" to "application/json", "cache-control" to "no-store"))

    private fun edge(colo: String, source: PlaceSource) = FleetPlace(provider = "Cloudflare", colo = colo, locatedBy = listOf(source))

    private fun code(value: dynamic, shape: Regex): String = (value as? String)?.takeIf { it.matches(shape) }.orEmpty()

    private val Colo = Regex("[A-Z]{3}")
    private val Country = Regex("[A-Z]{2}")
}
