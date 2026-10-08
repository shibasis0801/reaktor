package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable
data class SpanReadReceipt(
    val version: Int = 1,
    val database: String,
    val windowMinutes: Int,
    val limit: Int,
    val columns: List<String>,
    val rows: JsonArray,
)
