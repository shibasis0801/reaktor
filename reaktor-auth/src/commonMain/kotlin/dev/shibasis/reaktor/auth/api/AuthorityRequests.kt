package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.kernel.AuthorityGrant
import dev.shibasis.reaktor.auth.kernel.AuthorityResolution
import dev.shibasis.reaktor.auth.kernel.AuthorityTarget
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Environment
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import kotlinx.serialization.Serializable

@Serializable
data class AuthorityGrantsRequest(
    val sourceAppId: String,
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Environment.PROD,
) : Request() {
    override fun toString() = "AuthorityGrantsRequest(sourceAppId=$sourceAppId, environment=$environment)"
}

@Serializable
data class AuthorityGrantsResponse(
    val grants: List<AuthorityGrant> = emptyList(),
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf("Cache-Control" to "no-store"),
) : Response()

@Serializable
data class AuthorityResolveRequest(
    val sourceAppId: String,
    val target: AuthorityTarget,
    val permission: String,
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Environment.PROD,
) : Request() {
    override fun toString() = "AuthorityResolveRequest(sourceAppId=$sourceAppId, target=$target, permission=$permission, environment=$environment)"
}

@Serializable
data class AuthorityResolveResponse(
    val resolution: AuthorityResolution? = null,
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf("Cache-Control" to "no-store"),
) : Response() {
    override fun toString() = "AuthorityResolveResponse(statusCode=$statusCode, resolution=redacted)"
}
