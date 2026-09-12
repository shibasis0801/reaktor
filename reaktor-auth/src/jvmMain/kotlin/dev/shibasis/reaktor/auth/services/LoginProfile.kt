package dev.shibasis.reaktor.auth.services

import dev.shibasis.reaktor.auth.api.LoginRequest
import kotlinx.serialization.json.*

/** Display metadata is untrusted; credentials and authority claims never belong in a stored profile. */
internal fun LoginRequest.resolvedProfile(): JsonObject {
    val incoming = profile as? JsonObject
    val limits = mapOf("givenName" to 256, "familyName" to 256, "emailId" to 320, "imageUrl" to 4096)
    val fields = linkedMapOf<String, JsonElement>()
    limits.forEach { (name, limit) ->
        val value = incoming?.get(name) as? JsonPrimitive
        if (value?.isString == true && value.content.length <= limit) fields[name] = value
    }
    givenName?.takeIf { it.isNotBlank() && it.length <= 256 }?.let { fields["givenName"] = JsonPrimitive(it) }
    familyName?.takeIf { it.isNotBlank() && it.length <= 256 }?.let { fields["familyName"] = JsonPrimitive(it) }
    return JsonObject(fields)
}
