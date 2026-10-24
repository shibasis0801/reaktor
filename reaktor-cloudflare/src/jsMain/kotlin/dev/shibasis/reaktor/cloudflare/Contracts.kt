package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.graph.ContractRole
import dev.shibasis.reaktor.graph.contracts
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.contract.Call
import dev.shibasis.reaktor.service.contract.Exchange
import dev.shibasis.reaktor.service.contract.HttpLink
import dev.shibasis.reaktor.service.contract.Inbound
import dev.shibasis.reaktor.service.contract.Route
import dev.shibasis.reaktor.service.contract.Served
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

fun Hono.mount(served: Served<*>, island: String? = null): Hono = mount(served.calls.map { Mounted(served, it, island) })

fun Hono.mount(graph: Graph): Hono = mount(graph.contracts().filter { it.role == ContractRole.Server }.flatMap { node ->
    val served = requireNotNull(node.served)
    served.calls.map { Mounted(served, it, graph.label.ifBlank { null }) }
})

private class Mounted(val served: Served<*>, val call: Call<*, *>, val island: String?)

private fun Hono.mount(routes: List<Mounted>): Hono = apply {
    routes.sortedWith(compareBy<Mounted>({ it.call.binding.specificity }, { it.served.service.id })).forEach { route ->
        on(route.call.binding.method.name, route.call.binding.path.toHonoRoute()) { context -> route.served.respond(route.call, context, route.island) }
    }
}

private val Route.specificity: String get() = path.split('/').joinToString("") { if (it.startsWith('{')) "1" else "0" }

fun Graph.toHono(): Hono = Hono().mount(this)

fun Hono.fallback(served: Served<*>, call: Call<*, *>): Hono =
    on(call.binding.method.name, "/*") { context -> served.respond(call, context, null) }

fun bindingLink(binding: String, baseUrl: String = "https://$binding.binding"): HttpLink = HttpLink(baseUrl, bindingHttpClient(binding))

fun durableObjectLink(namespace: String, name: String): HttpLink = HttpLink(durableObjectUrl(name), durableObjectHttpClient(namespace))

val Exchange<*, *>.cloudflareOrNull: CloudflareContext? get() = attributes[CloudflareContextAttribute] as? CloudflareContext

val Exchange<*, *>.cloudflare: CloudflareContext
    get() = cloudflareOrNull ?: error("${subject} did not arrive through a Cloudflare adapter, so it has no Cloudflare context")

operator fun <T : Any> Exchange<*, *>.get(binding: Binding<T>): T = cloudflare[binding]

@OptIn(DelicateCoroutinesApi::class)
private fun Served<*>.respond(call: Call<*, *>, context: HonoContext, island: String?) = GlobalScope.promise {
    val body = runCatching { context.req.text().await() }.getOrNull()
    val attributes = buildMap<String, Any?> {
        put(CloudflareContextAttribute, CloudflareContext(context.env, context.executionCtx, context))
        island?.let { put(ServiceCall.IslandAttribute, it) }
    }
    val inbound = Inbound(toStringMap(context.req.param()), toListMap(context.req.queries()), toStringMap(context.req.header()), body, attributes)
    val outbound = handle(call, inbound)
    workerResponse(outbound.body, outbound.status, outbound.headers)
}

private fun toListMap(source: dynamic): Map<String, List<String>> {
    if (source == null) return emptyMap()
    val entries = js("Object.entries(source)") as Array<Array<dynamic>>
    return entries.associate { entry ->
        val values = entry[1]
        entry[0].toString() to if (js("Array.isArray(values)") as Boolean) (values as Array<dynamic>).map { it.toString() } else listOf(values.toString())
    }
}
