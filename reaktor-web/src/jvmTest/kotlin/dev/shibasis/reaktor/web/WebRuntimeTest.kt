package dev.shibasis.reaktor.web

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebRuntimeTest {
    private class Host : WebViewHost, WebViewController {
        var created = 0
        var closed = 0
        var loaded: WebContent? = null
        lateinit var context: WebHostContext
        override val features = setOf(WebFeature.Bundles)
        override fun create(): WebViewController = this.also { created++ }
        override fun configure(context: WebHostContext) { this.context = context }
        override fun load(content: WebContent) { loaded = content }
        override fun executeJavaScript(script: String) = Unit
        override fun close() { closed++ }
    }

    private fun withRuntime(test: (Graph, WebRuntime) -> Unit) {
        val app = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(app))
        try { test(graph, graph.Node { WebRuntime(it) }) }
        finally { graph.close(); app.close() }
    }

    @Test fun headlessGraphDoesNotCreateAPlatformViewAndOwnsItsSessions() = withRuntime { graph, runtime ->
        val first = Host()
        val second = Host()
        assertEquals(0, first.created)
        val session = runtime.open(first)
        val other = runtime.open(second)
        val html = WebContent.Html("<p>नमस्ते 🌍</p>")
        session.load(html)
        assertEquals(html, first.loaded)
        assertEquals(html, session.state.value.requestedContent)
        session.close()
        session.close()
        assertEquals(1, first.closed)
        graph.close()
        assertEquals(1, second.closed)
        assertTrue(other.state.value.closed)
        assertFailsWith<IllegalStateException> { runtime.open(Host()) }
        assertFailsWith<IllegalStateException> { session.load(html) }
    }

    @Test fun detachingRuntimeReleasesViews() = withRuntime { graph, runtime ->
        val host = Host()
        runtime.open(host)
        graph.detach(runtime)
        assertEquals(1, host.closed)
        assertFailsWith<IllegalStateException> { runtime.open(host) }
    }

    @Test fun graphClosingDuringNativeCreationReleasesTheUnregisteredView() = withRuntime { graph, runtime ->
        val host = Host()
        assertFailsWith<IllegalStateException> {
            runtime.open(object : WebViewHost {
                override fun create(): WebViewController {
                    graph.close()
                    return host
                }
            })
        }
        assertEquals(1, host.closed)
    }

    @Test fun oneFailedReleaseDoesNotLeakOtherViews() = withRuntime { _, runtime ->
        val host = Host()
        runtime.open(object : WebViewHost {
            override fun create(): WebViewController = object : WebViewController {
                override fun load(content: WebContent) = Unit
                override fun executeJavaScript(script: String) = Unit
                override fun close(): Unit = error("release failed")
            }
        })
        runtime.open(host)
        assertFailsWith<IllegalStateException> { runtime.close() }
        assertEquals(1, host.closed)
        runtime.close()
    }

    @Test fun unsafeUrlSchemesAndNullTerminatedPayloadsAreRejected() {
        listOf("file:///etc/passwd", "javascript:alert(1)", "https://example.com\u0000other").forEach {
            assertFailsWith<IllegalArgumentException> { WebContent.Url(it) }
        }
        assertFailsWith<IllegalArgumentException> { WebContent.Html("\u0000") }
    }

    @Test fun documentNavigationStaysWithinTheRequestedBundleRevision() = withRuntime { _, runtime ->
        val host = Host()
        val session = runtime.open(host)
        session.load(WebContent.Html("<p>Inline document</p>"))
        assertTrue(host.context.allowsNavigation("about:blank#section"))
        assertTrue(!host.context.allowsNavigation("about:blank/other"))
        val app = WebApp("probe", "v1", WebAssets(mapOf("/index.html" to WebAsset("<p>App</p>".encodeToByteArray(), "text/html"))))
        session.load(WebContent.Bundle(app))
        assertTrue(host.context.allowsNavigation(app.origin + "/v1/index.html"))
        assertTrue(!host.context.allowsNavigation(app.origin + "/v2/index.html"))
        assertTrue(!host.context.allowsNavigation("about:blank"))
    }
}
