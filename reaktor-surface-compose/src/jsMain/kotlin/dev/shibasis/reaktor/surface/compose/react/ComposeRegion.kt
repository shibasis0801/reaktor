@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.shibasis.reaktor.surface.compose.react

import androidx.compose.runtime.*
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.ui.window.LocalComposeWindow
import org.w3c.dom.HTMLElement
import react.*

/** Mount a Compose Surface region in an ordinary React DOM host.
 * Owns the renderer, resize observation, and disposal across StrictMode replay.
 * Compose 1.12.1's public ComposeViewport returns Unit; its composition-local
 * window is the only disposal handle. This is the sole version-specific access.
 */
fun useComposeRegion(host: RefObject<*>, content: @Composable () -> Unit): Boolean {
    val latest = useMemo { mutableStateOf(content) }
    useLayoutEffectWithCleanup { latest.value = content }
    val (ready, setReady) = useState(false)
    useLayoutEffectOnceWithCleanup {
        val container = host.current.unsafeCast<HTMLElement>()
        val region = kotlinx.browser.document.createElement("div") as HTMLElement
        region.style.width = "100%"; region.style.height = "100%"
        container.appendChild(region)
        var live = true
        var dispose: (() -> Unit)? = null
        var width = region.clientWidth; var height = region.clientHeight
        var windowWidth = kotlinx.browser.window.innerWidth; var windowHeight = kotlinx.browser.window.innerHeight
        val measure = {
            val nextWidth = region.clientWidth; val nextHeight = region.clientHeight
            val nextWindowWidth = kotlinx.browser.window.innerWidth; val nextWindowHeight = kotlinx.browser.window.innerHeight
            val changed = nextWidth != width || nextHeight != height
            val nativeResize = nextWindowWidth != windowWidth || nextWindowHeight != windowHeight
            width = nextWidth; height = nextHeight; windowWidth = nextWindowWidth; windowHeight = nextWindowHeight
            if (live && changed && !nativeResize) kotlinx.browser.window.dispatchEvent(org.w3c.dom.events.Event("resize"))
        }
        val observer = js("new ResizeObserver(measure)")
        observer.observe(region)
        val tab: (dynamic) -> Unit = { event ->
            if (event.key == "Tab") repeat(3) { frame -> kotlinx.browser.window.setTimeout({
                if (live && kotlinx.browser.document.activeElement === kotlinx.browser.document.body) {
                    val nodes = region.querySelectorAll("*")
                    val canvas: dynamic = (0 until nodes.length).map { nodes.item(it).asDynamic().shadowRoot?.querySelector("canvas") }.firstOrNull { it != null }
                    canvas?.focus(js("({ preventScroll: true })"))
                }
            }, 16 * frame) }
        }
        region.addEventListener("keydown", tab, true)
        ComposeViewport(region) {
            val window = LocalComposeWindow.current
            SideEffect {
                if (live) { dispose = { window?.dispose() }; setReady(true) }
                else kotlinx.browser.window.setTimeout({ window?.dispose(); region.remove() }, 0)
            }
            if (live) latest.value()
        }
        onCleanup { live = false; observer.disconnect(); dispose?.invoke(); region.remove() }
    }
    return ready
}
