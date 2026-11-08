package dev.shibasis.reaktor.cloud.cloudflare

interface CloudflareAiRequest {
    val workspace: String
    val account: String
    val action: String
    val gateway: String?
    val model: String?
    val system: String?
    val prompt: String?
}
