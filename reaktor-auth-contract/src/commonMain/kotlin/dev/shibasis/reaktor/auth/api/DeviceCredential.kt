package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Environment
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import kotlinx.serialization.Serializable
import kotlin.js.JsExport

@JsExport
@Serializable
@Suppress("NON_EXPORTABLE_TYPE")
data class DeviceCredentialRequest(
    val appId: String,
    val deviceId: String,
    val signatureKey: ByteArray,
    val cipherSuite: Int,
    val proof: ByteArray,
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Environment.PROD,
) : Request()

@JsExport
@Serializable
data class DeviceCredentialResponse(
    val credential: ByteArray? = null,
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf(),
) : Response()
