package dev.shibasis.reaktor.web

import android.app.Activity
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.koinApplication
import org.junit.Assert.*

class WebViewProbeActivity : Activity()
@Serializable private class DeviceRequest(val text: String) : Request()
@Serializable private class DeviceResponse(val text: String) : Response()

@RunWith(AndroidJUnit4::class)
class AndroidWebViewHostTest {
    @Test fun packagedModulesTypedBridgeEvaluationAndDisposal() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context, WebViewProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val koin = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koin))
        val runtime = graph.Node { WebRuntime(it) }
        val service = object : Service() {
            override val contract = ServiceContract("device.probe")
            val echo = server(PostHandler, "/echo", "probe.echo", DeviceRequest.serializer(), DeviceResponse.serializer()) { DeviceResponse(it.text) }
        }
        val bridge = graph.Node { WebBridge(it, service, MutableStateFlow(WebGrant(
            "device", "v1", "physical-test", "test", Environment.STAGE, setOf("probe.echo"))),
            authorize = { grant, _ -> grant.principal == "physical-test" }) }
        val html = """<!doctype html><html><head><meta charset="utf-8">
            <script type="module" src="./reaktor/bridge.js"></script><script type="module" src="./probe.js"></script>
            </head><body><input aria-label="Text input"><p id="result">waiting</p></body></html>"""
        val js = """const run = async () => { try { const result = await window.reaktor.invoke("probe.echo",{text:"नमस्ते 🌍"}); document.getElementById("result").textContent=result.text; } catch(e) { document.getElementById("result").textContent=String(e); } };
            if (window.__reaktorConfig) run(); else window.addEventListener("reaktor-ready",run,{once:true});"""
        val app = WebApp("device", "v1", WebAssets(mapOf(
            "/index.html" to WebAsset(html.encodeToByteArray(), "text/html"),
            "/probe.js" to WebAsset(js.encodeToByteArray(), "text/javascript"),
        )) + WebBridgeAssets)
        lateinit var session: WebSession
        try {
            instrumentation.runOnMainSync {
                val host = AndroidWebViewHost(activity)
                session = runtime.open(host, WebViewOptions(profile = WebProfile.Persistent("default")), bridge)
                activity.setContentView(host.view)
                session.load(WebContent.Bundle(app))
            }
            assertTrue("Provider needs provenance-aware messages", WebFeature.Bridge in session.features)
            withTimeout(20_000) { while (session.state.value.loading) delay(50) }
            var result = ""
            try { withTimeout(15_000) {
                while (result != "नमस्ते 🌍") {
                    result = session.evaluate("document.getElementById('result').textContent").jsonPrimitive.content
                    delay(50)
                }
            } } catch (error: TimeoutCancellationException) {
                val diagnostic = session.evaluate("JSON.stringify({html:document.body.innerHTML,config:window.__reaktorConfig,client:typeof window.reaktor,native:typeof window.ReaktorNative})")
                throw AssertionError("Bridge did not complete: $diagnostic; state=${session.state.value}", error)
            }
            assertEquals(app.origin + "/v1/index.html", session.state.value.committedUrl)
            assertTrue(session.features.contains(WebFeature.Evaluation))
            assertEquals("2", session.evaluate("1+1").jsonPrimitive.content)
            assertFalse(session.allowsNavigation("https://evil.example"))
            instrumentation.runOnMainSync { graph.close() }
            assertTrue(session.state.value.closed)
        } finally {
            instrumentation.runOnMainSync { runtime.close(); bridge.close(); activity.finish() }
            graph.close(); koin.close()
        }
    }
}
