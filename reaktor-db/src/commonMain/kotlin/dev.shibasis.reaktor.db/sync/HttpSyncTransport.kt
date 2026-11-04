package dev.shibasis.reaktor.db.sync

import dev.shibasis.reaktor.io.network.http
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/**
 * [SyncTransport] over HTTPS to a server that speaks [SyncPush] and [SyncPage] at `{baseUrl}/sync/…`
 * — `reaktor-cloudflare`'s `D1SyncLog` behind a Worker, or anything else that does.
 *
 * [token] is asked for on every call, so a refreshed credential is picked up without reconnecting.
 * No token, a 401 or a 403 is [SyncFailure.Kind.SignedOut]; no answer at all is
 * [SyncFailure.Kind.Offline]; anything else the server says is [SyncFailure.Kind.Server].
 */
class HttpSyncTransport(
    private val baseUrl: String,
    private val token: suspend () -> String?,
    private val client: HttpClient = http,
) : SyncTransport {

    override suspend fun push(ops: List<SyncOp>) {
        val body = Sync.json.encodeToString(SyncPush.serializer(), SyncPush(ops))
        val response = call { bearer ->
            client.post("${baseUrl.trimEnd('/')}/sync/push") {
                header(HttpHeaders.Authorization, "Bearer $bearer")
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
        read(response) { Sync.json.decodeFromString(SyncPushed.serializer(), it) }
    }

    override suspend fun pull(after: String?, limit: Int): SyncPage {
        val response = call { bearer ->
            client.get("${baseUrl.trimEnd('/')}/sync/pull") {
                header(HttpHeaders.Authorization, "Bearer $bearer")
                if (after != null) parameter("after", after)
                parameter("limit", limit)
            }
        }
        return read(response) { Sync.json.decodeFromString(SyncPage.serializer(), it) }
    }

    private suspend fun call(request: suspend (String) -> HttpResponse): HttpResponse {
        val bearer = token() ?: throw SyncFailure(SyncFailure.Kind.SignedOut, "Not signed in")
        return try {
            request(bearer)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw SyncFailure(SyncFailure.Kind.Offline, error.message ?: "No response", error)
        }
    }

    private suspend fun <T> read(response: HttpResponse, decode: (String) -> T): T {
        val status = response.status
        if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) {
            throw SyncFailure(SyncFailure.Kind.SignedOut, "The server refused the credential (${status.value})")
        }
        if (!status.isSuccess()) throw SyncFailure(SyncFailure.Kind.Server, "The server answered ${status.value}")
        return try {
            decode(response.bodyAsText())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw SyncFailure(SyncFailure.Kind.Server, "Could not read the server's answer", error)
        }
    }
}
