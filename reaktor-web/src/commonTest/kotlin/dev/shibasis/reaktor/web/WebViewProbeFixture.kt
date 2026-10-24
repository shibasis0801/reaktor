package dev.shibasis.reaktor.web
import dev.shibasis.reaktor.service.*
import kotlinx.serialization.*
import kotlinx.coroutines.flow.MutableStateFlow
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.service.contract.Served

@Serializable private class ProbeRequest(val text: String) : Request()
@Serializable private class ProbeResponse(val text: String) : Response()
private open class ProbeApi : Service("reaktor.probe") {
    open val echo by post<ProbeRequest, ProbeResponse>("/echo", name = "probe.echo")
}
internal fun probeBridge(graph: dev.shibasis.reaktor.graph.core.Graph): WebBridge {
    val served = Served(object : ProbeApi() {
        override val echo = super.echo.handle { request -> ProbeResponse("Typed bridge received: " + request.text) }
    })
    return WebBridge(graph, served, MutableStateFlow(WebGrant("probe", "v1", "local-probe", "probe",
        Environment.STAGE, setOf("probe.echo"))), authorize = { grant, operation ->
        grant.principal == "local-probe" && operation.operation == "probe.echo"
    })
}
internal fun probeContent(): WebContent.Bundle {
    val html = """
        <!doctype html><html><head><meta charset="utf-8"><title>Reaktor WebView probe</title>
        <script type="module" src="./reaktor/bridge.js"></script>
        <script type="module" src="./probe.js"></script></head><body>
        <h1>System WKWebView inside AWT</h1><p id="status">Bundle loaded — नमस्ते 🌍</p>
        <p id="bridge">Waiting for the typed bridge…</p>
        <input aria-label="Keyboard and IME probe" placeholder="Type here to check focus and IME">
        <button id="input-check">Check input</button>
        </body></html>
    """.trimIndent()
    val script = """
        const echo = async () => {
          try {
            const result = await window.reaktor.invoke("probe.echo", {text:"नमस्ते 🌍"});
            document.getElementById("bridge").textContent = result.text;
          } catch (e) { document.getElementById("bridge").textContent = String(e); }
        };
        document.getElementById("input-check").onclick = () => document.getElementById("status").textContent = "Native input received";
        if (window.__reaktorConfig) echo(); else window.addEventListener("reaktor-ready", echo, {once:true});
    """.trimIndent()
    return WebContent.Bundle(WebApp("probe", "v1", WebAssets(mapOf(
        "/index.html" to WebAsset(html.encodeToByteArray(), "text/html"),
        "/probe.js" to WebAsset(script.encodeToByteArray(), "text/javascript"),
    )) + WebBridgeAssets))
}
