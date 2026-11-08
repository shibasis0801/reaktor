package dev.shibasis.reaktor.cloud.cloudflare

import dev.shibasis.reaktor.cloud.http.ServiceTokenRequest

interface WorkerRequest : ServiceTokenRequest {
    val endpoint: String
    val operation: String
    val store: WorkerStore?
    val queryFile: String?
    val resultFile: String?
    val maxRows: Int
    val explain: Boolean
    val catalog: WorkerStoreCatalog?
    val effect: String
    val inputFile: String?
    val inputDigest: String?
}
