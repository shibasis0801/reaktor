package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.service.Request
import kotlinx.serialization.json.JsonElement

/**
 * Typed ClickHouse client over a Workers VPC HTTP service binding.
 *
 * ClickHouse speaks HTTP on `:8123` — SQL goes in the request body, results come
 * back in the response (shape controlled by a trailing `FORMAT` clause). The
 * Worker reaches the private in-cluster ClickHouse through a Workers VPC service
 * binding (Cloudflare Tunnel), so there is no public hostname and no raw socket:
 * VPC bindings are HTTP `fetch()` only, and ClickHouse's HTTP interface is the
 * idiomatic edge path. The native protocol on `:9000` is deliberately not used.
 *
 * The `http://clickhouse/` origin below is a placeholder — a VPC service binding
 * ignores the host and routes to its configured private origin; only the method,
 * path, query, headers and body matter.
 *
 * Reference: https://clickhouse.com/docs/interfaces/http
 */
class ClickHouse internal constructor(
    private val service: WorkerService,
    private val user: String?,
    private val password: String?,
    private val database: String?,
) {
    private fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        user?.let { put("X-ClickHouse-User", it) }
        password?.let { put("X-ClickHouse-Key", it) }
        database?.let { put("X-ClickHouse-Database", it) }
        putAll(extra)
    }

    /**
     * Execute arbitrary SQL and return the raw response body. Add an explicit
     * `FORMAT` clause (e.g. `FORMAT JSON`, `FORMAT TabSeparated`) to control the
     * response shape; with no `FORMAT` ClickHouse defaults to `TabSeparated`.
     */
    suspend fun execute(sql: String): String {
        val response = service.fetch(
            url = CLICKHOUSE_ORIGIN,
            method = "POST",
            body = sql,
            headers = headers(TEXT_BODY),
        )
        val body = response.text()
        check(response.ok) { "ClickHouse HTTP ${response.status}: ${body.take(ERROR_PREVIEW)}" }
        return body
    }

    /** Run a query with `FORMAT JSON` and return the parsed JSON envelope. */
    suspend fun queryJson(sql: String): JsonElement {
        val response = service.fetch(
            url = CLICKHOUSE_ORIGIN,
            method = "POST",
            body = ensureFormat(sql, "JSON"),
            headers = headers(TEXT_BODY),
        )
        val body = response.text()
        check(response.ok) { "ClickHouse HTTP ${response.status}: ${body.take(ERROR_PREVIEW)}" }
        return json.parseToJsonElement(body)
    }

    /**
     * Insert rows using `JSONEachRow`. Each element of [rows] must be a single
     * JSON object serialized to one line. The table name is validated to a safe
     * identifier charset before interpolation.
     */
    suspend fun insertJsonEachRow(table: String, rows: List<String>) {
        require(rows.isNotEmpty()) { "ClickHouse insert: no rows" }
        require(SAFE_IDENTIFIER.matches(table)) { "Unsafe ClickHouse table name: $table" }
        val payload = buildString {
            append("INSERT INTO ").append(table).append(" FORMAT JSONEachRow\n")
            append(rows.joinToString("\n"))
        }
        val response = service.fetch(
            url = CLICKHOUSE_ORIGIN,
            method = "POST",
            body = payload,
            headers = headers(TEXT_BODY),
        )
        val body = response.text()
        check(response.ok) { "ClickHouse insert HTTP ${response.status}: ${body.take(ERROR_PREVIEW)}" }
    }

    /** Liveness probe via ClickHouse `/ping` (`Ok.\n` on success). */
    suspend fun ping(): Boolean =
        service.fetch(url = CLICKHOUSE_ORIGIN + "ping", method = "GET", headers = headers()).ok

    private companion object {
        const val CLICKHOUSE_ORIGIN = "http://clickhouse/"
        const val ERROR_PREVIEW = 500
        val TEXT_BODY = mapOf("Content-Type" to "text/plain; charset=utf-8")
        val SAFE_IDENTIFIER = Regex("^[A-Za-z0-9_.]+$")
    }
}

private fun ensureFormat(sql: String, format: String): String {
    val trimmed = sql.trimEnd().trimEnd(';').trimEnd()
    return if (trimmed.contains(Regex("(?i)\\bFORMAT\\s+\\w+\\s*$"))) trimmed else "$trimmed FORMAT $format"
}

/**
 * Declarative ClickHouse binding: the VPC HTTP service name plus the secret
 * bindings that carry its credentials and the default database. Mirrors the
 * [postgres] binding shape so workers compose it the same way.
 */
class ClickHouseBinding internal constructor(
    internal val serviceName: String,
    internal val user: SecretBinding?,
    internal val password: SecretBinding?,
    internal val database: String?,
)

fun clickHouse(
    name: String,
    user: SecretBinding? = null,
    password: SecretBinding? = null,
    database: String? = null,
): ClickHouseBinding = ClickHouseBinding(name, user, password, database)

fun CloudflareContext.clickHouse(binding: ClickHouseBinding): ClickHouse =
    ClickHouse(
        service = requireService(binding.serviceName),
        user = binding.user?.let { secretOrNull(it.name) },
        password = binding.password?.let { secretOrNull(it.name) },
        database = binding.database,
    )

suspend inline fun <T> CloudflareContext.clickHouse(
    binding: ClickHouseBinding,
    block: suspend ClickHouse.() -> T,
): T = clickHouse(binding).block()

suspend inline fun <T> CloudflareRequest.clickHouse(
    binding: ClickHouseBinding,
    block: suspend ClickHouse.() -> T,
): T = context.clickHouse(binding, block)

suspend inline fun <T> Request.clickHouse(
    binding: ClickHouseBinding,
    block: suspend ClickHouse.() -> T,
): T = context.clickHouse(binding, block)
