package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
data class WebPerformance(
    val directory: String,
    val metrics: List<SizeMetric>,
    val budgets: List<SizeBudget>,
    val adapter: String,
    val measurements: String,
    val report: String,
)
