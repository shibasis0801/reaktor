package dev.shibasis.reaktor.io.service

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.io.network.RoutePattern
import io.ktor.http.HttpMethod
import kotlinx.serialization.*
/* You design your own sealed hierarchy for Request/Response */
interface BaseRequest {
    val headers: MutableMap<String, String>
    val queryParams: MutableMap<String, String> // todo needs encode/decode
    val pathParams: MutableMap<String, String>
}

interface BaseResponse {
    val headers: MutableMap<String, String>
    var statusCode: StatusCode
}

abstract class RequestHandler<Request: BaseRequest, Response: BaseResponse>(
    val method: HttpMethod,
    val route: String
) {
    val routePattern = RoutePattern.from(route)
    inline fun url(request: Request, vararg extraPathParams: Pair<String, String>): String {
        return routePattern.fill(request.pathParams + extraPathParams)
    }

    abstract suspend operator fun invoke(request: Request): Response
    companion object {
        inline operator fun <Request: BaseRequest, Response: BaseResponse> invoke(
            method: HttpMethod,
            rawRoute: String,
            crossinline block: suspend RequestHandler<Request, Response>.(Request) -> Response
        ): RequestHandler<Request, Response> = object: RequestHandler<Request, Response>(method, rawRoute) {
            override suspend fun invoke(request: Request): Response = block(request)
        }
    }
}

abstract class Service: Adapter<Unit>(Unit) {
    val handlers = arrayListOf<RequestHandler<*, *>>()

    inline fun <Request: BaseRequest, Response: BaseResponse> GetHandler(
        route: String,
        crossinline block: suspend RequestHandler<Request, Response>.(Request) -> Response
    ): RequestHandler<Request, Response> {
        return RequestHandler(HttpMethod.Get, route, block).also { handlers.add(it) }
    }

    inline fun <Request: BaseRequest, Response: BaseResponse> PostHandler(
        route: String,
        crossinline block: suspend RequestHandler<Request, Response>.(Request) -> Response
    ): RequestHandler<Request, Response> {
        return RequestHandler(HttpMethod.Post, route, block).also { handlers.add(it) }
    }
}

@Serializable
class DownloadImageRequest(
    val fileName: String,
    override val headers: MutableMap<String, String> = hashMapOf(),
    override val queryParams: MutableMap<String, String> = hashMapOf(),
    override val pathParams: MutableMap<String, String> = hashMapOf()
): BaseRequest

@Serializable
class DownloadImageResponse(
    val data: ByteArray,
    override val headers: MutableMap<String, String> = hashMapOf(),
    override var statusCode: StatusCode = StatusCode.OK
): BaseResponse

@Serializable
class UploadImageRequest(
    val data: ByteArray,
    override val headers: MutableMap<String, String> = hashMapOf(),
    override val queryParams: MutableMap<String, String> = hashMapOf(),
    override val pathParams: MutableMap<String, String> = hashMapOf()
): BaseRequest

@Serializable
class UploadImageResponse(
    override val headers: MutableMap<String, String> = hashMapOf(),
    override var statusCode: StatusCode = StatusCode.OK
): BaseResponse

abstract class ImageService: Service() {
    abstract val downloadImage: RequestHandler<DownloadImageRequest, DownloadImageResponse>
    abstract val uploadImage: RequestHandler<UploadImageRequest, UploadImageResponse>
}

class ImageServiceClient: ImageService() {
    override val downloadImage: RequestHandler<DownloadImageRequest, DownloadImageResponse> = GetHandler("/{fileName}") { request ->
        val route = url(request, "fileName" to request.fileName)
        DownloadImageResponse(byteArrayOf())
    }

    override val uploadImage: RequestHandler<UploadImageRequest, UploadImageResponse> = PostHandler("/") {
        UploadImageResponse()
    }
}

class ImageServiceServer: ImageService() {
    override val downloadImage: RequestHandler<DownloadImageRequest, DownloadImageResponse> = GetHandler("/image") {
        DownloadImageResponse(byteArrayOf())
    }

    override val uploadImage: RequestHandler<UploadImageRequest, UploadImageResponse> = PostHandler("/image") {
        UploadImageResponse()
    }
}


suspend fun t(imageService: ImageService) {
    val x = imageService.downloadImage(DownloadImageRequest("shibasis.jpg"))
}















