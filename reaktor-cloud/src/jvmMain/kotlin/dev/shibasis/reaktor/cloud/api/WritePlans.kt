package dev.shibasis.reaktor.cloud.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

object WritePlans {
    fun plan(target: String, request: PlannedRequest, live: JsonElement?, proposed: JsonElement?, ignore: Set<String> = emptySet(), liveVersion: String? = null) =
        WritePlan(target, request, live != null, JsonDiff.changes(live, proposed, ignore), digest(target, request), liveVersion)

    fun digest(target: String, request: PlannedRequest): String = MessageDigest.getInstance("SHA-256")
        .digest("$target\n${request.method}\n${request.url}\n${request.body?.let(::canonical).orEmpty()}".toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.keys.sorted().joinToString(",", "{", "}") { "${JsonPrimitive(it)}:${canonical(element.getValue(it))}" }
        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        else -> element.toString()
    }

    fun confirm(plan: WritePlan, approved: String) = check(digest(plan.target, plan.request) == approved) {
        "The live request for ${plan.target} no longer matches the approved plan; plan it again"
    }
}
