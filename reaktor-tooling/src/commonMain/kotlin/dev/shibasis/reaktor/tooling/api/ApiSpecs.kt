package dev.shibasis.reaktor.tooling.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

object ApiSpecs {
    private val Methods = listOf("get", "put", "post", "delete", "patch", "head", "options")
    private const val GoogleScopes = "https://www.googleapis.com/auth/"
    private val GoogleReads = setOf("get", "list", "aggregatedList", "search", "getIamPolicy", "testIamPermissions", "batchGet", "listAll")

    fun openApi(provider: String, document: JsonObject, digest: String, baseUrl: String? = null): ApiSurface {
        val info = document.obj("info")
        val server = baseUrl ?: document.array("servers").firstOrNull()?.let { (it as? JsonObject)?.text("url") } ?: ""
        val components = document.obj("components")
        val parameters = components.obj("parameters")
        val operations = document.obj("paths").flatMap { (path, item) ->
            val node = item as? JsonObject ?: return@flatMap emptyList()
            val shared = node.array("parameters").mapNotNull { parameter(it, parameters) }
            Methods.mapNotNull { method ->
                val operation = node[method] as? JsonObject ?: return@mapNotNull null
                val own = operation.array("parameters").mapNotNull { parameter(it, parameters) }
                val merged = (shared.filter { base -> own.none { it.name == base.name && it.location == base.location } } + own)
                val id = operation.text("operationId") ?: "${method}_${path.trim('/').replace('/', '_').replace("{", "").replace("}", "")}"
                ApiOperation(
                    id = id,
                    api = provider,
                    method = method.uppercase(),
                    path = path,
                    summary = operation.text("summary") ?: operation.text("description")?.lineSequence()?.firstOrNull().orEmpty(),
                    baseUrl = server,
                    parameters = merged,
                    body = operation.obj("requestBody").obj("content").let { content ->
                        (content["application/json"] as? JsonObject ?: content.values.firstOrNull() as? JsonObject)?.get("schema")
                    },
                    tags = operation.array("tags").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                    effect = openApiEffect(method, path),
                    deprecated = (operation["deprecated"] as? JsonPrimitive)?.booleanOrNull == true,
                    permissions = operation.array("x-api-token-group").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .ifEmpty { listOfNotNull(operation.text("x-oauth-scope")) },
                )
            }
        }
        return ApiSurface(provider, info.text("title") ?: provider, info.text("version") ?: "", operations, digest,
            components.obj("schemas"))
    }

    fun discovery(document: JsonObject, digest: String): ApiSurface {
        val name = document.text("name").orEmpty()
        val version = document.text("version").orEmpty()
        val api = "gcp:$name.$version"
        val baseUrl = document.text("baseUrl") ?: (document.text("rootUrl").orEmpty() + document.text("servicePath").orEmpty())
        val global = document.obj("parameters")
        val operations = mutableListOf<ApiOperation>()
        fun walk(resource: JsonObject) {
            resource.obj("methods").values.forEach { element ->
                val method = element as? JsonObject ?: return@forEach
                val id = method.text("id") ?: return@forEach
                val httpMethod = method.text("httpMethod") ?: "GET"
                val parameters = method.obj("parameters").map { (parameterName, spec) ->
                    val node = spec as? JsonObject
                    ApiParameter(parameterName, node?.text("location") ?: "query", (node?.get("required") as? JsonPrimitive)?.booleanOrNull == true,
                        node?.text("type") ?: "string", node?.text("description").orEmpty())
                } + listOf("fields", "quotaUser").filter { it in global }.map { ApiParameter(it, "query", false, "string") }
                val path = method.text("flatPath")?.takeIf { flat -> parameters.filter { it.location == "path" }.all { "{${it.name}}" in flat } }
                    ?: method.text("path").orEmpty()
                operations += ApiOperation(
                    id = id,
                    api = api,
                    method = httpMethod,
                    path = path,
                    summary = method.text("description")?.lineSequence()?.firstOrNull().orEmpty(),
                    baseUrl = baseUrl,
                    parameters = parameters.map { parameter -> if ("{+${parameter.name}}" in path) parameter.copy(reserved = true) else parameter },
                    body = method["request"],
                    tags = listOf(id.split('.').drop(1).dropLast(1).joinToString(".")),
                    effect = when {
                        httpMethod == "GET" || id.substringAfterLast('.') in GoogleReads -> ApiEffect.Read
                        httpMethod == "DELETE" -> ApiEffect.Delete
                        else -> ApiEffect.Write
                    },
                    deprecated = (method["deprecated"] as? JsonPrimitive)?.booleanOrNull == true,
                    permissions = method.array("scopes").mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.removePrefix(GoogleScopes) },
                )
            }
            resource.obj("resources").values.forEach { child -> (child as? JsonObject)?.let(::walk) }
        }
        walk(document)
        return ApiSurface("gcp", document.text("title") ?: api, version, operations, digest, document.obj("schemas"))
    }

    fun openApiEffect(method: String, path: String): ApiEffect = when {
        method.equals("get", true) || method.equals("head", true) -> ApiEffect.Read
        method.equals("post", true) && path.endsWith("/graphql") -> ApiEffect.Read
        method.equals("delete", true) -> ApiEffect.Delete
        else -> ApiEffect.Write
    }

    private fun parameter(element: JsonElement, shared: JsonObject): ApiParameter? {
        val node = (element as? JsonObject)?.let { candidate ->
            candidate.text("\$ref")?.substringAfterLast('/')?.let { shared[it] as? JsonObject } ?: candidate
        } ?: return null
        val schema = node.obj("schema")
        return ApiParameter(
            name = node.text("name") ?: return null,
            location = node.text("in") ?: "query",
            required = (node["required"] as? JsonPrimitive)?.booleanOrNull == true,
            type = schema.text("type") ?: schema.text("\$ref")?.substringAfterLast('/') ?: "string",
            description = node.text("description").orEmpty(),
        )
    }

    private fun JsonObject?.obj(key: String): JsonObject = this?.get(key) as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonObject?.array(key: String): List<JsonElement> = (this?.get(key) as? JsonArray) ?: emptyList()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
