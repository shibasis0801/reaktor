@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.shibasis.reaktor.web

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.koin.dsl.koinApplication
import platform.Foundation.NSLog
import platform.UIKit.UIViewController

fun createDeviceWebViewProbe(): UIViewController = DeviceWebViewProbe()

private class DeviceWebViewProbe : UIViewController(nibName = null, bundle = null) {
    private val koin = koinApplication {}
    private val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koin))
    private val runtime = graph.Node { WebRuntime(it) }
    private val bridge = graph.Node { probeBridge(it) }
    private val host = DarwinWebViewHost()
    private val session = runtime.open(host, bridge = bridge)

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.addSubview(host.view)
        session.load(probeContent())
        runtime.coroutineScope.launch(Dispatchers.Main) {
            try {
                withTimeout(20_000) {
                    while (session.state.value.loading) delay(50)
                    while (session.evaluate("document.getElementById('bridge').textContent").jsonPrimitive.contentOrNull != "Typed bridge received: नमस्ते 🌍") delay(50)
                }
                check(session.evaluate("1+1").jsonPrimitive.contentOrNull == "2")
                NSLog("REAKTOR_WEBVIEW_DEVICE_PASS: bundle modules, typed bridge, Unicode, evaluation")
            } catch (error: Throwable) { NSLog("REAKTOR_WEBVIEW_DEVICE_FAIL: " + error.toString()) }
        }
    }
    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        host.view.setFrame(view.bounds)
    }
    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        graph.close()
        koin.close()
    }
}
