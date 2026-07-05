package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.core.framework.json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal fun parseTokenPolicyList(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()?.let { return it.normalizedTokenPolicyList() }
    return raw.split(' ', ',').normalizedTokenPolicyList()
}

internal fun encodeTokenPolicyList(values: List<String>): String =
    json.encodeToString(values.normalizedTokenPolicyList())

internal fun List<String>.normalizedTokenPolicyList(default: List<String> = emptyList()): List<String> =
    map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { default }
        .distinct()
        .sorted()
