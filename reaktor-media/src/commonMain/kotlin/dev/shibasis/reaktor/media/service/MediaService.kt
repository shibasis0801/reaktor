package dev.shibasis.reaktor.media.service

import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.service.Environment
import dev.shibasis.reaktor.graph.service.GetHandler
import dev.shibasis.reaktor.graph.service.PostHandler
import dev.shibasis.reaktor.graph.service.Request
import dev.shibasis.reaktor.graph.service.Response
import dev.shibasis.reaktor.graph.service.Service
import kotlinx.serialization.Serializable

@Serializable
sealed class MediaRequest : Request() {
    @Serializable
    class Download(
        val filePath: String,
        override val headers: MutableMap<String, String> = hashMapOf(),
        override val queryParams: MutableMap<String, String> = hashMapOf("filePath" to filePath),
        override val pathParams: MutableMap<String, String> = hashMapOf(),
        override var environment: Environment = Environment.STAGE,
    ) : MediaRequest()

    @Serializable
    class Upload(
        val filePath: String,
        val data: ByteArray,
        val contentType: String? = null,
        override val headers: MutableMap<String, String> = hashMapOf(),
        override val queryParams: MutableMap<String, String> = hashMapOf(),
        override val pathParams: MutableMap<String, String> = hashMapOf("filePath" to filePath),
        override var environment: Environment = Environment.STAGE,
    ) : MediaRequest()
}

@Serializable
sealed class MediaResponse(
    override val statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf(),
) : Response() {
    @Serializable
    data class Download(
        val data: ByteArray = byteArrayOf(),
        val contentType: String = "application/octet-stream",
    ) : MediaResponse(StatusCode.OK)

    @Serializable
    data class Upload(
        val message: String = "",
        val link: String = "",
    ) : MediaResponse(StatusCode.CREATED)

    @Serializable
    sealed class Failure(private val hack: StatusCode) : MediaResponse(hack) {
        @Serializable
        data class InvalidRequest(val message: String) : Failure(StatusCode.BAD_REQUEST)

        @Serializable
        data class NotFound(val message: String) : Failure(StatusCode.NOT_FOUND)

        @Serializable
        data class ServerError(val message: String) : Failure(StatusCode.INTERNAL_SERVER_ERROR)
    }
}

abstract class MediaService(baseUrl: String = "") : Service(baseUrl) {
    abstract val downloadMedia: GetHandler<MediaRequest.Download, MediaResponse>
    abstract val uploadMedia: PostHandler<MediaRequest.Upload, MediaResponse>
}

open class MediaServiceClient(baseUrl: String) : MediaService(baseUrl) {
    override val downloadMedia = GetHandler<MediaRequest.Download, MediaResponse>("/download")
    override val uploadMedia = PostHandler<MediaRequest.Upload, MediaResponse>("/upload")

    suspend fun download(filePath: String): Result<MediaResponse.Download> = runCatching {
        when (val response = downloadMedia(MediaRequest.Download(filePath))) {
            is MediaResponse.Download -> response
            is MediaResponse.Failure -> failMedia(response)
            else -> error("Unexpected response '${response::class.simpleName}'")
        }
    }

    suspend fun upload(
        filePath: String,
        data: ByteArray,
        contentType: String? = null,
    ): Result<MediaResponse.Upload> = runCatching {
        when (val response = uploadMedia(MediaRequest.Upload(filePath, data, contentType))) {
            is MediaResponse.Upload -> response
            is MediaResponse.Failure -> failMedia(response)
            else -> error("Unexpected response '${response::class.simpleName}'")
        }
    }
}

private fun failMedia(response: MediaResponse.Failure): Nothing =
    when (response) {
        is MediaResponse.Failure.InvalidRequest -> error(response.message)
        is MediaResponse.Failure.NotFound -> error(response.message)
        is MediaResponse.Failure.ServerError -> error(response.message)
    }
