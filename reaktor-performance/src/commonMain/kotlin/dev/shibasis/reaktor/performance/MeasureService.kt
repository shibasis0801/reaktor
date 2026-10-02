package dev.shibasis.reaktor.performance

import dev.shibasis.reaktor.io.network.http
import dev.shibasis.reaktor.service.GetHandler
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.Service
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

data class MeasureQuery(
    val from: String? = null,
    val to: String? = null,
    val versions: List<String> = emptyList(),
    val versionCodes: List<String> = emptyList(),
) {
    init {
        require((from == null) == (to == null)) { "Supply both ends of the time range" }
        if (from != null && to != null) require(Instant.parse(from) < Instant.parse(to)) { "Time range must increase" }
        require(versions.size == versionCodes.size) { "Each version needs its build code" }
        require((versions + versionCodes).all { it.isNotBlank() && ',' !in it && it.none { char -> char < ' ' || char == '\u007f' } }) { "Version values must be nonempty and contain no commas" }
    }
}

@Serializable
class MeasureRequest : Request()

class MeasureService(
    baseUrl: String,
    private val accessToken: String,
    httpClient: HttpClient = http,
) : Service(baseUrl, httpClient) {
    init {
        val origin = Url(baseUrl)
        require(origin.protocol.name == "https" || (origin.protocol.name == "http" && origin.host in setOf("localhost", "127.0.0.1", "::1"))) {
            "Use HTTPS, or HTTP on loopback for the local Measure server"
        }
        require(origin.user == null && origin.password == null && origin.parameters.isEmpty() && origin.fragment.isEmpty()) { "Supply an API origin without credentials, query or fragment" }
        require(accessToken.isNotBlank() && accessToken.none { it == '\r' || it == '\n' }) { "A dashboard access token is required" }
    }

    private val filtersRead = client(GetHandler, "/apps/{id}/filters", "measure.filters", MeasureRequest.serializer(), MeasureAppFilters.serializer())
    private val metricsRead = client(GetHandler, "/apps/{id}/metrics", "measure.metrics", MeasureRequest.serializer(), MeasureMetrics.serializer())
    private val sessionsRead = client(GetHandler, "/apps/{id}/sessions", "measure.sessions", MeasureRequest.serializer(), MeasurePage.serializer(MeasureSession.serializer()))
    private val errorsRead = client(GetHandler, "/apps/{id}/errorGroups", "measure.errors", MeasureRequest.serializer(), MeasurePage.serializer(MeasureErrorGroup.serializer()))
    private val namesRead = client(GetHandler, "/apps/{id}/spans/roots/names", "measure.spanNames", MeasureRequest.serializer(), MeasureSpanNames.serializer())
    private val spansRead = client(GetHandler, "/apps/{id}/spans", "measure.spans", MeasureRequest.serializer(), MeasurePage.serializer(MeasureSpan.serializer()))
    private val sessionRead = client(GetHandler, "/apps/{id}/sessions/{sessionId}", "measure.session", MeasureRequest.serializer(), MeasureSessionDetail.serializer())
    private val traceRead = client(GetHandler, "/apps/{id}/traces/{traceId}", "measure.trace", MeasureRequest.serializer(), MeasureTrace.serializer())
    private val errorEventsRead = client(GetHandler, "/apps/{id}/errorGroups/{errorGroupId}/errors", "measure.errorEvents", MeasureRequest.serializer(), MeasurePage.serializer(kotlinx.serialization.json.JsonObject.serializer()))

    suspend fun filters(appId: String) = filtersRead(request(appId)).checked { failure }
    suspend fun metrics(appId: String, query: MeasureQuery = MeasureQuery()): MeasureMetrics {
        val selection = if (query.versions.isEmpty()) {
            val versions = filters(appId).versions.orEmpty()
            if (versions.isEmpty()) return MeasureMetrics(recordingsAvailable = false)
            query.copy(versions = versions.map { it.name }, versionCodes = versions.map { it.code })
        } else query
        return metricsRead(request(appId, selection)).checked { failure }
    }
    suspend fun sessions(appId: String, query: MeasureQuery = MeasureQuery(), offset: Int = 0, search: String = "") =
        sessionsRead(request(appId, query, offset).apply { if (search.isNotBlank()) queryParams["free_text"] = search }).checked { failure }
    suspend fun problems(appId: String, problem: MeasureProblem, query: MeasureQuery = MeasureQuery(), offset: Int = 0) =
        errorsRead(request(appId, query, offset).apply {
            queryParams["type"] = problem.errorType
            if (problem.severities.isNotEmpty()) queryParams["severity"] = problem.severities.joinToString(",")
        }).checked { failure }
    suspend fun spanNames(appId: String, query: MeasureQuery = MeasureQuery()) = namesRead(request(appId, query)).checked { failure }
    suspend fun spans(appId: String, name: String, query: MeasureQuery = MeasureQuery(), offset: Int = 0) =
        spansRead(request(appId, query, offset, versionExpression = true).apply {
            require(name.isNotBlank()) { "Select a span name" }
            queryParams["span_name"] = name
        }).checked { failure }
    suspend fun session(appId: String, sessionId: String) = sessionRead(request(appId).apply { pathParams["sessionId"] = identifier(sessionId) }).checked { failure }
    suspend fun trace(appId: String, traceId: String) = traceRead(request(appId).apply { pathParams["traceId"] = identifier(traceId) }).checked { failure }
    suspend fun problemEvents(appId: String, errorGroupId: String, query: MeasureQuery = MeasureQuery(), offset: Int = 0) =
        errorEventsRead(request(appId, query, offset).apply { pathParams["errorGroupId"] = identifier(errorGroupId) }).checked { failure }

    private fun request(appId: String, query: MeasureQuery = MeasureQuery(), offset: Int? = null, versionExpression: Boolean = false) = MeasureRequest().apply {
        pathParams["id"] = identifier(appId)
        headers["Authorization"] = "Bearer $accessToken"
        headers["Content-Type"] = "application/json; charset=utf-8"
        query.from?.let { queryParams["from"] = it }
        query.to?.let { queryParams["to"] = it }
        if (query.versions.isNotEmpty()) {
            queryParams["versions"] = query.versions.joinToString(",")
            queryParams["version_codes"] = query.versionCodes.joinToString(",")
            if (versionExpression) queryParams["filter_expr"] = query.versions.zip(query.versionCodes).joinToString(" OR ") { (version, code) ->
                "(version_name:in:${JsonPrimitive(version)} AND version_code:in:${JsonPrimitive(code)})"
            }
        }
        offset?.let {
            require(it >= 0) { "Page offset must be nonnegative" }
            queryParams["offset"] = it.toString()
            queryParams["limit"] = PageSize.toString()
        }
    }

    private fun identifier(value: String): String {
        require(value.length in 1..128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "Invalid Measure identifier" }
        return value
    }

    private fun <T : Response> T.checked(failure: T.() -> String?): T {
        if (isSuccess) return this
        val code = transportStatusCode.code
        val reason = failure()?.takeIf(String::isNotBlank)?.take(300)
        throw MeasureRequestFailed(code, when (code) {
            401 -> "Measure rejected the access token. Local tokens last 30 minutes."
            403 -> "This account cannot read the selected Measure app."
            404 -> reason?.let { "Measure could not find it: $it" } ?: "Measure app or recording was not found."
            else -> reason?.let { "Measure answered $code: $it" } ?: "Measure answered $code."
        })
    }

    companion object { const val PageSize = 50 }
}

class MeasureRequestFailed(val status: Int, message: String) : IllegalStateException(message) {
    val unauthorized: Boolean get() = status == 401
}
