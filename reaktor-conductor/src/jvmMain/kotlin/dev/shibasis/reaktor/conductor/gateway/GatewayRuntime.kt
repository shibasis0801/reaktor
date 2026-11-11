package dev.shibasis.reaktor.conductor.gateway

import dev.shibasis.reaktor.conductor.AgentRuntime
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.cloud.cloudflare.CloudflareAccounts
import dev.shibasis.reaktor.cloud.cloudflare.CloudflareAi
import dev.shibasis.reaktor.cloud.cloudflare.CloudflareLogin
import java.io.File

class GatewayRuntime(
    connect: (File) -> CloudflareAi? = ::workspaceAi,
    gateway: suspend (CloudflareAi) -> String? = ::reaktorGateway,
    clock: () -> Long = System::currentTimeMillis,
) : AgentRuntime by ChatToolRuntime(RuntimeKind.Gateway, clock, { root ->
    val ai = connect(root) ?: error("No Cloudflare account is named in this workspace. Set CLOUDFLARE_ACCOUNT_ID or add account_id to a wrangler config.")
    val via = runCatching { gateway(ai) }.getOrNull()
    ChatRoute(via?.let { "AI Gateway $it" } ?: "Workers AI, no gateway", DefaultModel) { body, metadata -> ai.complete(body, via, metadata) }
}) {
    companion object {
        const val DefaultModel: String = "@cf/moonshotai/kimi-k2.7-code"
        const val AgentGateway: String = "reaktor"

        fun workspaceAi(root: File): CloudflareAi? = CloudflareAccounts.of(root)?.let { CloudflareAi(it, CloudflareLogin.forWorkspace(root)) }

        suspend fun reaktorGateway(ai: CloudflareAi): String? = ai.gateways().firstOrNull { it.id == AgentGateway }?.id
    }
}
