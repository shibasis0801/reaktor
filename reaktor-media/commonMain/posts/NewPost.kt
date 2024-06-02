package app.mehmaan.media.posts

import app.mehmaan.core.network.network
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
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

    network.post("http://10.0.2.2:8787/rafale.jpg") {
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


