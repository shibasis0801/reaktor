package dev.shibasis.dependeasy.model

import java.io.Serializable as JavaSerializable
import kotlinx.serialization.Serializable

@Serializable enum class SizeMeasure { Bytes, Gzip, Brotli, Count, ContentMatches }
@Serializable data class SizeMetric(val name: String, val pathPattern: String, val measure: SizeMeasure,
    val contentPattern: String? = null, val required: Boolean = false) : JavaSerializable
@Serializable data class SizeBudget(val metric: String, val maximum: Long, val unit: String = "bytes") : JavaSerializable
@Serializable data class ArtifactMeasurement(val path: String, val bytes: Long, val gzip: Long, val brotli: Long, val sha256: String)
@Serializable data class ArtifactSizeResult(val schema: Int = 1, val node: NodeId,
    val artifacts: List<ArtifactMeasurement>, val metrics: Map<String, Long>, val missing: List<String>, val budgets: List<SizeBudget> = emptyList())
