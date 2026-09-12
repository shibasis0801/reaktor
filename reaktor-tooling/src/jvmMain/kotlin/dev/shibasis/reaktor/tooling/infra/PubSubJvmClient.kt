package dev.shibasis.reaktor.tooling.infra

import com.google.auth.oauth2.GoogleCredentials
import dev.shibasis.reaktor.tooling.database.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.security.MessageDigest

/** Read projection only: never creates subscriptions, pulls, seeks, acknowledges, or alters IAM. */
internal class PubSubJvmClient(private val session: InfrastructureSession) {
    fun read(connection: DatabaseConnection.GoogleProject, query: PubSubQuery, limit: Int): QueryReceipt {
        query.validate()
        require(connection.project.matches(Regex("[a-z][a-z0-9-]{4,61}[a-z0-9]")))
        val started = System.nanoTime()
        val http = BoundedHttp(session)
        if (query.action == PubSubReadAction.Recent) {
            val endpoint = URI(requireNotNull(connection.observations[query.subscription]) { "This subscription has no application observation source" })
            require(endpoint.scheme == "https" && endpoint.userInfo == null && endpoint.fragment == null && endpoint.query == null)
            val auth = connection.observationAuthority?.let { ServiceTokenClient(session).token(it, emptyMap()) }
            val response = Json.parseToJsonElement(http.request(endpoint, headers = auth?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty())).jsonObject
            check(response["ok"]?.jsonPrimitive?.booleanOrNull == true && response["projectId"]?.jsonPrimitive?.content == connection.project)
            val deliveries = response["deliveries"]?.jsonArray.orEmpty().map { it.jsonObject }
                .filter { it["subscription"]?.jsonPrimitive?.content == "projects/${connection.project}/subscriptions/${query.subscription}" }
            val (page, cursor) = pubSubObservationPage(deliveries, query.pageToken, limit)
            return receipt(page, cursor, started).copy(warnings = listOf(
                "Recent application observations from one receiver process or Worker isolate; history is bounded and may reset. Refresh can reach another instance.",
                "No subscription pull, acknowledgment, seek, or delivery change was performed."))
        }
        val file = File(connection.credentialFile)
        require(file.isFile && file.length() in 1..65_536) { "Google credential file is unavailable" }
        val credentials = file.inputStream().use { GoogleCredentials.fromStream(it) }
            .createScoped(listOf("https://www.googleapis.com/auth/pubsub", "https://www.googleapis.com/auth/monitoring.read"))
        credentials.refreshIfExpired()
        val token = requireNotNull(credentials.accessToken?.tokenValue) { "Google authentication failed" }
        val headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
        val params = linkedMapOf("pageSize" to limit.toString())
        query.pageToken?.let { params["pageToken"] = it }
        val path: String
        val rowsKey: String
        var end: Instant? = null
        if (query.action == PubSubReadAction.Statistics) {
            end = query.endTime?.let(Instant::parse) ?: Instant.now()
            params["filter"] = "metric.type = \"pubsub.googleapis.com/subscription/${query.metric}\"" +
                (query.subscription?.let { " AND resource.labels.subscription_id = \"$it\"" } ?: "")
            params["interval.startTime"] = end.minusSeconds(3600).toString()
            params["interval.endTime"] = end.toString()
            params["view"] = "FULL"
            path = "https://monitoring.googleapis.com/v3/projects/${connection.project}/timeSeries"
            rowsKey = "timeSeries"
        } else {
            rowsKey = if (query.action == PubSubReadAction.Topics) "topics" else "subscriptions"
            path = "https://pubsub.googleapis.com/v1/projects/${connection.project}/$rowsKey"
        }
        val uri = URI(path + "?" + params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" })
        val response = Json.parseToJsonElement(http.request(uri, headers = headers)).jsonObject
        val objects = response[rowsKey]?.jsonArray.orEmpty().map { it.jsonObject }
        val rows = if (query.action != PubSubReadAction.Statistics) objects else objects.flatMap { series ->
            series["points"]?.jsonArray.orEmpty().map { point -> buildJsonObject {
                put("metric", series.getValue("metric")); put("resource", series.getValue("resource"))
                put("unit", series["unit"] ?: JsonNull); put("metric_kind", series["metricKind"] ?: JsonNull)
                put("interval", point.jsonObject.getValue("interval")); put("value", point.jsonObject.getValue("value"))
            } }
        }
        require(rows.size <= limit) { "Pub/Sub response exceeds requested page size" }
        val result = receipt(rows, response["nextPageToken"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank), started)
        return result.copy(metrics = result.metrics + listOfNotNull(end?.let { QueryMetric("Window end", it.toString(), source = QueryMetricSource.Client) }),
            warnings = if (query.action == PubSubReadAction.Statistics) listOf("Cloud Monitoring samples over the last hour; timestamps and metric kind describe each value. Missing samples are unknown, not zero.") else emptyList())
    }

    private fun receipt(rows: List<JsonObject>, cursor: String?, started: Long): QueryReceipt {
        val names = rows.flatMap { it.keys }.distinct()
        return QueryReceipt(provider = "PubSub", columns = names.map { QueryColumn(it, "json") },
            rows = rows.map { row -> names.map { row[it] ?: JsonNull } }, cursor = cursor, truncated = cursor != null,
            metrics = listOf(QueryMetric("Adapter elapsed", ((System.nanoTime() - started) / 1_000_000).toString(), "ms", QueryMetricSource.Client)))
    }
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
}

internal fun pubSubObservationPage(deliveries: List<JsonObject>, token: String?, limit: Int): Pair<List<JsonObject>, String?> {
    require(limit in 1..500)
    data class Position(val timestamp: Long, val identity: String, val occurrence: Int)
    val ordering = compareBy<Position> { it.timestamp }.thenBy { it.identity }.thenBy { it.occurrence }
    val before = token?.let {
        require(it.matches(Regex("[0-9]{1,19}:[a-f0-9]{64}:[0-9]{1,9}"))) { "Invalid observation cursor" }
        val parts = it.split(':')
        Position(parts[0].toLong(), parts[1], parts[2].toInt())
    }
    val occurrences = mutableMapOf<Pair<Long, String>, Int>()
    val ordered = deliveries.map { row ->
        val timestamp = requireNotNull(row["receivedAtEpochMillis"]?.jsonPrimitive?.longOrNull)
        require(timestamp >= 0)
        // Include the delivery identity and payload in a digest: tied timestamps and redeliveries
        // remain pageable without embedding message contents in the continuation token.
        val identity = MessageDigest.getInstance("SHA-256").digest(row.toString().toByteArray()).toHexString()
        val key = timestamp to identity
        val occurrence = occurrences.getOrDefault(key, 0)
        occurrences[key] = occurrence + 1
        Position(timestamp, identity, occurrence) to row
    }.sortedWith { a, b -> ordering.compare(b.first, a.first) }
        .filter { before == null || ordering.compare(it.first, before) < 0 }
    val page = ordered.take(limit)
    val cursor = if (ordered.size > limit) page.last().first.let { "${it.timestamp}:${it.identity}:${it.occurrence}" } else null
    return page.map { it.second } to cursor
}
