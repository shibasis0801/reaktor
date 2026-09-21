package dev.shibasis.reaktor.media.service

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.io.network.http
import dev.shibasis.reaktor.media.gallery.GalleryAdapter
import dev.shibasis.reaktor.media.gallery.MediaPick
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable
private data class UploadEnvelope(val message: String? = null, val link: String? = null, val error: String? = null)

class MediaUploader(
    baseUrl: String,
    private val httpClient: HttpClient = http,
    /**
     * Auth (and any other) headers for the upload. Media endpoints are typically authenticated,
     * and an unauthenticated POST fails with 401 rather than storing anything.
     */
    private val headers: suspend () -> Map<String, String> = { emptyMap() },
) {
    private val baseUrl: String = baseUrl.trimEnd('/')

    suspend fun upload(
        path: String,
        bytes: ByteArray,
        contentType: String,
        @Suppress("UNUSED_PARAMETER") fileName: String = path.substringAfterLast('/'),
    ): Result<String> = runCatching {
        val url = "$baseUrl/${path.trimStart('/')}"
        val parsedType = runCatching { ContentType.parse(contentType) }.getOrNull()
            ?: ContentType.Application.OctetStream
        val uploadHeaders = headers()
        val response = httpClient.post(url) {
            contentType(parsedType)
            uploadHeaders.forEach { (name, value) -> header(name, value) }
            setBody(bytes)
        }
        val body = response.bodyAsText()
        val envelope = runCatching { json.decodeFromString(UploadEnvelope.serializer(), body) }.getOrNull()
        envelope?.link
            ?: error(envelope?.error ?: "Upload failed (status=${response.status.value}): $body")
    }
}

suspend fun GalleryAdapter<*>.pickAndUpload(
    uploader: MediaUploader,
    pathBuilder: (MediaPick) -> String,
): Result<String>? {
    val pick = pickImage() ?: return null
    return uploader.upload(
        path = pathBuilder(pick),
        bytes = pick.bytes,
        contentType = pick.mimeType,
        fileName = pick.suggestedName ?: pathBuilder(pick).substringAfterLast('/'),
    )
}
