package dev.shibasis.reaktor.service.place

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.service.FleetPlace
import dev.shibasis.reaktor.service.PlaceSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class CloudLocator(
    private val egress: URI? = null,
    private val metadata: URI = URI("http://metadata.google.internal/computeMetadata/v1/"),
    private val cluster: Boolean = System.getenv("KUBERNETES_SERVICE_HOST") != null,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
) {
    fun locate(): FleetPlace = gcp() or egress()

    fun gcp(): FleetPlace {
        val zone = text(metadata.resolve("instance/zone"), "Metadata-Flavor" to "Google")?.substringAfterLast('/')
            ?.takeIf { it.matches(GcpZone) } ?: return FleetPlace()
        return FleetPlace(provider = "GCP", region = zone.substringBeforeLast('-'), zone = zone, locatedBy = listOf(PlaceSource.Metadata))
    }

    fun egress(): FleetPlace {
        val place = egress?.let(::text)?.let { runCatching { json.decodeFromString(FleetPlace.serializer(), it) }.getOrNull() } ?: return FleetPlace()
        return FleetPlace(country = place.country, city = place.city, latitude = place.latitude, longitude = place.longitude,
            locatedBy = listOf(PlaceSource.Egress))
    }

    fun reached(endpoint: URI, observer: FleetPlace): FleetPlace {
        val host = endpoint.host?.lowercase().orEmpty()
        val region = AwsRegion.find(host)?.value
        return when {
            cluster && (host.isNotEmpty() && '.' !in host || host.endsWith(".svc") || host.endsWith(".svc.cluster.local")) ->
                observer.copy(zone = "", locatedBy = listOf(PlaceSource.Cluster))
            region != null -> FleetPlace(provider = "AWS", region = region, locatedBy = listOf(PlaceSource.Endpoint))
            host.endsWith(".googleapis.com") -> FleetPlace(provider = "GCP", region = "global", locatedBy = listOf(PlaceSource.Endpoint))
            else -> FleetPlace()
        }
    }

    private fun text(uri: URI, vararg headers: Pair<String, String>): String? = runCatching {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        http.send(request, HttpResponse.BodyHandlers.ofString()).takeIf { it.statusCode() == 200 }?.body()
    }.getOrNull()

    private companion object {
        val GcpZone = Regex("[a-z]+-[a-z]+[0-9]+-[a-z]")
        val AwsRegion = Regex("(?<![a-z])(af|ap|ca|eu|il|me|mx|sa|us)-(central|north|northeast|northwest|south|southeast|southwest|east|west)-[0-9](?![0-9])")
    }
}
