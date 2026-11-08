package dev.shibasis.reaktor.service.place

import dev.shibasis.reaktor.service.FleetPlace
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant

class AirportPlaces(
    private val cache: Path,
    private val source: URI = URI("https://davidmegginson.github.io/ourairports-data/airports.csv"),
    private val maxAge: Duration = Duration.ofDays(30),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build(),
    private val now: () -> Instant = Instant::now,
) {
    private var places: Map<String, FleetPlace>? = null
    private var loadedAt = Instant.EPOCH

    fun place(code: String): FleetPlace? = table()[code]

    @Synchronized
    private fun table(): Map<String, FleetPlace> {
        val held = places
        if (held != null && loadedAt.plus(maxAge).isAfter(now())) return held
        if (!fresh()) refresh()
        return (if (Files.isRegularFile(cache)) Files.newBufferedReader(cache).useLines(::airportPlaces) else held.orEmpty())
            .also { places = it; loadedAt = now() }
    }

    private fun fresh() = Files.isRegularFile(cache) && Files.getLastModifiedTime(cache).toInstant().plus(maxAge).isAfter(now())

    private fun refresh() {
        val body = runCatching {
            http.send(HttpRequest.newBuilder(source).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                .takeIf { it.statusCode() == 200 }?.body()
        }.getOrNull() ?: return
        if (airportPlaces(body.decodeToString().lineSequence()).isEmpty()) return
        Files.createDirectories(cache.parent)
        val written = Files.write(cache.resolveSibling("${cache.fileName}.next"), body)
        Files.move(written, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

internal fun airportPlaces(lines: Sequence<String>): Map<String, FleetPlace> {
    val rows = lines.filter(String::isNotBlank).map(::csvFields).iterator()
    if (!rows.hasNext()) return emptyMap()
    val column = rows.next().withIndex().associate { (index, name) -> name to index }
    val needed = listOf("type", "latitude_deg", "longitude_deg", "iso_country", "municipality", "scheduled_service", "iata_code")
    if (!column.keys.containsAll(needed)) return emptyMap()
    fun List<String>.at(name: String) = getOrNull(column.getValue(name)).orEmpty()
    return rows.asSequence().mapNotNull { row ->
        val code = row.at("iata_code").takeIf { it.matches(Iata) } ?: return@mapNotNull null
        val latitude = row.at("latitude_deg").toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return@mapNotNull null
        val longitude = row.at("longitude_deg").toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return@mapNotNull null
        val rank = Sizes.indexOf(row.at("type")).let { if (it < 0) Sizes.size else it } * 2 + if (row.at("scheduled_service") == "yes") 0 else 1
        Triple(code, rank, FleetPlace(country = row.at("iso_country").takeIf { it.matches(Country) }.orEmpty(),
            city = row.at("municipality").take(64), latitude = latitude, longitude = longitude))
    }.groupBy { it.first }.mapValues { (_, candidates) -> candidates.minBy { it.second }.third }
}

internal fun csvFields(line: String): List<String> {
    val fields = ArrayList<String>()
    val field = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            quoted && char == '"' && line.getOrNull(index + 1) == '"' -> { field.append('"'); index++ }
            char == '"' -> quoted = !quoted
            char == ',' && !quoted -> { fields += field.toString(); field.clear() }
            else -> field.append(char)
        }
        index++
    }
    return fields + field.toString()
}

private val Iata = Regex("[A-Z]{3}")
private val Country = Regex("[A-Z]{2}")
private val Sizes = listOf("large_airport", "medium_airport", "small_airport")
