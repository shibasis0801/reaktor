package dev.shibasis.reaktor.cloud.clickhouse

import dev.shibasis.reaktor.cloud.kubernetes.ClusterService

interface SpanRequest {
    val connection: ClusterService
    val database: String
    val windowMinutes: Int
    val limit: Int
    val filter: SpanReadFilter
}
