package dev.shibasis.reaktor.core.cloudflare

import dev.shibasis.reaktor.core.server.cloudflare.Env
import dev.shibasis.reaktor.core.server.cloudflare.ExecutionContext
import org.w3c.fetch.Request
import org.w3c.fetch.Response
import kotlin.js.Promise

interface CloudflareWorker {
    fun fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response>
}