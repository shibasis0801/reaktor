package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.service.ServiceCall

suspend fun decision(name: String, value: String) {
    if (!name.matches(Regex("[A-Za-z0-9_.:-]{1,64}")) || value.length > 256) return
    val exchange = exchangeOrNull() ?: return
    val previous = exchange.decisions()
    if (previous.size >= 16 && name !in previous) return
    exchange.attributes[ServiceCall.DecisionsAttribute] = previous + (name to value)
}

internal fun Exchange<*, *>.decisions(): Map<String, String> =
    (attributes[ServiceCall.DecisionsAttribute] as? Map<*, *>)?.entries.orEmpty()
        .mapNotNull { (key, value) -> if (key is String && value is String) key to value else null }.toMap()
