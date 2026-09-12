package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.util.UUID

class WorkerJvmClient(private val session: InfrastructureSession) {
    private val http = BoundedHttp(session)
    private val json = Json { ignoreUnknownKeys = true }

    fun execute(op: InfrastructureOperation.WorkerCall, environment: Map<String, String>): String {
        require(op.operation.matches(Regex("[a-z0-9.-]{1,100}")))
        val endpoint = URI(op.endpoint)
        val tokenEndpoint = URI(op.tokenEndpoint)
        require(endpoint.scheme == "https" && endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null)
        require(tokenEndpoint.scheme == "https" && tokenEndpoint.userInfo == null)
        val credentials = credentials(op.credentialFile, environment, setOf(op.clientIdKey, op.clientSecretKey))
        val request = buildJsonObject {
            put("grant_type", "client_credentials")
            put("clientId", requireNotNull(credentials[op.clientIdKey]) { "Worker client identity is unavailable" })
            put("clientSecret", requireNotNull(credentials[op.clientSecretKey]) { "Worker credential is unavailable" })
            put("audience", op.audience)
            put("scopes", JsonArray(op.scopes.map(::JsonPrimitive)))
            put("ttlSeconds", 300)
            put("environment", op.authEnvironment)
        }
        val tokenResponse = json.parseToJsonElement(http.request(tokenEndpoint, request.toString(),
            mapOf("Content-Type" to "application/json"), 65_536)).jsonObject
        val token = requireNotNull(tokenResponse["accessToken"]?.jsonPrimitive?.contentOrNull) { "Worker authentication failed" }
        val headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
        val catalog = json.decodeFromString<WorkerOperationCatalog>(http.request(URI("${op.endpoint.trimEnd('/')}/_reaktor/operations"), headers = headers, maxBytes = 65_536))
        catalog.requireRead(requireNotNull(environment["REAKTOR_ENVIRONMENT"]), op.operation)
        val requestId = UUID.randomUUID().toString()
        val result = json.decodeFromString<WorkerOperationResult>(http.request(
            URI("${op.endpoint.trimEnd('/')}/_reaktor/operations/${op.operation}?requestId=$requestId"), headers = headers, maxBytes = 1_048_576))
        result.requireMatches(catalog, requestId, op.operation)
        return Json.encodeToString(result)
    }

    private fun credentials(path: String?, environment: Map<String, String>, keys: Set<String>): Map<String, String> {
        if (keys.all { !environment[it].isNullOrBlank() }) return environment.filterKeys { it in keys }
        val fromFile = path?.let(::File)?.takeIf(File::isFile)?.let { file ->
            require(file.length() <= 32_768) { "Worker credential file exceeds limit" }
            file.readLines().mapNotNull { line ->
                val match = Regex("(?:export\\s+)?([A-Z_]+)=(.*)").matchEntire(line.trim()) ?: return@mapNotNull null
                val key = match.groupValues[1]
                if (key !in keys) return@mapNotNull null
                val raw = match.groupValues[2].trim()
                val value = when {
                    raw.startsWith("'") && raw.endsWith("'") -> raw.substring(1, raw.lastIndex)
                    raw.startsWith("\"") && raw.endsWith("\"") -> raw.substring(1, raw.lastIndex)
                    else -> raw
                }
                require(value.isNotBlank() && value.none { it.isISOControl() || it == '$' || it == '`' || it == '\\' }) { "Unsupported Worker credential file syntax" }
                key to value
            }.toMap()
        }.orEmpty()
        return fromFile + environment.filterKeys { it in keys }.filterValues(String::isNotBlank)
    }
}
