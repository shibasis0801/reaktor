package dev.shibasis.reaktor.conductor.workspace

import java.io.BufferedReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A public HTTPS front for [HybridConnector], for exactly as long as it is running.
 *
 * ChatGPT calls its connectors from OpenAI's servers, so the endpoint has to be reachable from the
 * internet — and the thing behind it is a laptop. Two properties follow from that and shape this:
 * the exposure is a **separate process**, so closing it is killing one command rather than
 * restarting the workspace, and it is **never started implicitly**, because nothing about opening a
 * workspace should publish it.
 *
 * A quick tunnel is the default because it is the honest shape for this: the hostname is random and
 * it dies with the process, so an address that leaks stops working the moment you stop planning. A
 * named tunnel on a domain you own is the right answer once this is routine; pass its arguments.
 */
class HybridTunnel private constructor(
    private val process: Process,
    /** The public origin, e.g. `https://random-words.trycloudflare.com`. */
    val origin: String,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    /** The address to paste into ChatGPT: public origin plus the connector's secret path. */
    fun url(connector: HybridConnector): String = connector.url(origin)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    companion object {
        /**
         * Starts `cloudflared` in front of [port] and waits for it to publish a hostname.
         *
         * Fails rather than returning a tunnel with no address: a half-started tunnel that is
         * reported as working sends the operator to ChatGPT with a URL that will never answer.
         */
        fun start(
            port: Int,
            binary: String = "cloudflared",
            arguments: List<String> = listOf("tunnel", "--url", "http://127.0.0.1:$port"),
            timeoutSeconds: Long = 60,
            /**
             * The public origin, when the caller already knows it.
             *
             * A named tunnel routes a hostname the operator chose, and reading it back out of a log
             * is guesswork about someone else's output format. Pass it and nothing is parsed.
             */
            origin: String? = null,
        ): HybridTunnel {
            require(port in 1..65535) { "Invalid port" }
            val process = ProcessBuilder(listOf(binary) + arguments).redirectErrorStream(true).start()
            val found = CompletableFuture<String>()
            // cloudflared announces the hostname on stderr, which is merged above. The reader owns
            // draining it either way: a tunnel whose output is never consumed blocks on a full pipe.
            val reader = Thread({
                runCatching {
                    process.inputStream.bufferedReader().use { stream ->
                        stream.forEachLineUntil(found) { line -> tunnelOrigin(line) }
                    }
                }
                found.complete("")
            }, "reaktor-hybrid-tunnel").apply { isDaemon = true; start() }

            val published = origin ?: runCatching { found.get(timeoutSeconds, TimeUnit.SECONDS) }.getOrDefault("")
            if (published.isBlank()) {
                process.destroyForcibly(); reader.interrupt()
                error("$binary did not publish a tunnel hostname within ${timeoutSeconds}s. " +
                    "Check that it is installed and signed in, or run it yourself and pass the origin.")
            }
            return HybridTunnel(process, published.trimEnd('/'))
        }

        private inline fun BufferedReader.forEachLineUntil(found: CompletableFuture<String>, extract: (String) -> String?) {
            while (!found.isDone) {
                val line = readLine() ?: return
                extract(line)?.let { found.complete(it); return }
            }
        }
    }
}

/**
 * The tunnel hostname in one line of cloudflared output, or null.
 *
 * Harder than it looks, and worth the care: cloudflared's very first line is a paragraph of prose
 * containing `https://www.cloudflare.com/website-terms/`, so anything that simply takes the first
 * URL it sees publishes the wrong address and every request comes back 301. A quick tunnel is
 * always on `trycloudflare.com`, which needs no other signal; a hostname on the operator's own
 * domain is only trusted from the banner box, where cloudflared prints an address and nothing else.
 */
internal fun tunnelOrigin(line: String): String? {
    val candidates = Regex("https://[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]").findAll(line).map { it.value }.toList()
    candidates.firstOrNull { it.endsWith(".trycloudflare.com", ignoreCase = true) }?.let { return it }
    if (!line.contains('|')) return null
    return candidates.firstOrNull { it.removePrefix("https://").lowercase() !in cloudflareInformationalHosts }
}

/** Hosts cloudflared mentions to explain itself, which are never the tunnel. */
private val cloudflareInformationalHosts = setOf(
    "cloudflare.com", "www.cloudflare.com", "developers.cloudflare.com",
    "dash.cloudflare.com", "api.cloudflare.com", "blog.cloudflare.com",
)
