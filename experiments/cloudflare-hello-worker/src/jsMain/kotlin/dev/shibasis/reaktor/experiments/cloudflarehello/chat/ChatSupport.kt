package dev.shibasis.reaktor.experiments.cloudflarehello.chat

internal fun nextId(prefix: String): String = "$prefix-${js("globalThis.crypto.randomUUID()") as String}"

internal fun nowIsoString(): String = js("new Date().toISOString()") as String
