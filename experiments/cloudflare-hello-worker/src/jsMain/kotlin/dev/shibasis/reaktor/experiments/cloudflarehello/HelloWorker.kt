package dev.shibasis.reaktor.experiments.cloudflarehello

import dev.shibasis.reaktor.cloudflare.CloudflareDurableObject
import dev.shibasis.reaktor.cloudflare.Hono
import dev.shibasis.reaktor.cloudflare.int
import dev.shibasis.reaktor.cloudflare.mount
import dev.shibasis.reaktor.cloudflare.nest
import dev.shibasis.reaktor.cloudflare.putInt
import dev.shibasis.reaktor.cloudflare.toWorker
import dev.shibasis.reaktor.experiments.cloudflarehello.chat.ChatService
import dev.shibasis.reaktor.experiments.cloudflarehello.supabase.SupabaseService
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.JsExport
import kotlin.js.Promise

@JsExport
@OptIn(DelicateCoroutinesApi::class)
class HelloCounterDurableObject(
    state: Any,
    env: Any,
) : CloudflareDurableObject(state, env) {
    @Suppress("UNUSED_PARAMETER")
    fun fetch(request: Any): Promise<dynamic> = GlobalScope.promise {
        val next = (storage.int("count") ?: 0) + 1
        storage.putInt("count", next)
        text("legacy hello counter count=$next")
    }
}

@JsExport
object HelloWorker {
    private val worker =
        Hono()
            .mount(ExperimentRootService())
            .nest("/chat", ChatService())
            .nest("/supabase", SupabaseService())
            .toWorker()

    fun fetch(request: Any, env: Any, executionContext: Any): Any =
        worker.fetch(request, env, executionContext)
}
