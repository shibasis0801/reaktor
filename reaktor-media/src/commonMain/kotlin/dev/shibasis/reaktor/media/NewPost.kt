package dev.shibasis.reaktor.media

import dev.shibasis.reaktor.io.network.httpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.append
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


@Serializable
data class Post(
    val header: String,
    val content: String,
    val image: String,
)

suspend fun uploadPost(
    post: Post,
    imageBytes: ByteArray,
    contentType: ContentType
) {

    httpClient.post("http://10.0.2.2:8787/rafale.jpg") {
        contentType(ContentType.MultiPart.FormData)
        setBody(MultiPartFormDataContent(formData {
            append("data", Json.encodeToString(post), Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.Json)
                append(HttpHeaders.ContentDisposition, "form-data; name=\"data\"")
            })
            append("image", imageBytes, Headers.build {
                append(HttpHeaders.ContentType, ContentType.Image.JPEG)
                append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"rafale.jpg\"")
            })
        }, boundary = "WebAppBoundary"))

        onUpload { bytesSentTotal, contentLength ->
            println("Sent $bytesSentTotal bytes from $contentLength")
        }
    }
}


