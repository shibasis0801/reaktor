package dev.shibasis.reaktor.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import dev.shibasis.reaktor.ui.machinesignal.colors
import dev.shibasis.reaktor.web.WebContent
import dev.shibasis.reaktor.web.WebSessionState
import java.awt.EventQueue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class WebViewSurfaceTest {
    @Test
    fun themedInitialLoadRetainsTheEstablishedViewThroughRefreshAndFailure() {
        for (initialMode in listOf(MachineSignalMode.Dark, MachineSignalMode.Light)) {
            for (scale in listOf(1f, 2f)) EventQueue.invokeAndWait {
                var page by mutableStateOf(WebSessionState())
                var mode by mutableStateOf(initialMode)
                var generation by mutableStateOf(0)
                var visible = false
                var background = Color.Unspecified
                var mounted: Any? = null
                val requested = WebContent.Html("<main>Retained board</main>")
                val scene = ImageComposeScene(
                    width = (320 * scale).toInt(),
                    height = (120 * scale).toInt(),
                    density = Density(scale),
                ) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        key(generation) {
                            WebViewSurface(page, Modifier.fillMaxSize()) { shown, color ->
                                val identity = remember { Any() }
                                SideEffect { visible = shown; background = color; mounted = identity }
                                if (shown) Box(Modifier.fillMaxSize().background(MachineSignal.colors.surface)) {
                                    BasicText(
                                        "Retained board",
                                        style = TextStyle(fontSize = MachineSignal.Type.body, color = MachineSignal.colors.text),
                                    )
                                }
                            }
                        }
                    }
                }
                var time = 0L
                fun pixels(): IntArray {
                    Snapshot.sendApplyNotifications()
                    return scene.render(++time * 16_000_000L).use {
                        it.toComposeImageBitmap().toPixelMap().buffer.copyOf()
                    }
                }
                fun pending(): IntArray {
                    val expected = page
                    val image = pixels()
                    assertFalse(visible)
                    assertTrue(image.all { it == background.toArgb() })
                    assertEquals(expected, page)
                    return image
                }
                try {
                    pending()
                    val identity = mounted
                    page = page.copy(requestedContent = requested, loading = true)
                    val loading = pending()
                    assertSame(identity, mounted)
                    page = page.copy(committedUrl = "about:blank")
                    assertContentEquals(loading, pending())
                    page = page.copy(loading = false, error = "Page unavailable")
                    assertContentEquals(loading, pending())
                    page = page.copy(error = null)
                    val ready = pixels()
                    assertTrue(visible)
                    assertTrue(ready.any { it != background.toArgb() })
                    assertSame(identity, mounted)
                    assertSame(requested, page.requestedContent)
                    page = page.copy(loading = true)
                    assertContentEquals(ready, pixels())
                    assertTrue(visible)
                    assertSame(identity, mounted)
                    page = page.copy(loading = false, error = "Refresh unavailable")
                    assertContentEquals(ready, pixels())
                    assertTrue(visible)
                    assertSame(identity, mounted)
                    assertSame(requested, page.requestedContent)
                    val previousBackground = background
                    mode = if (mode == MachineSignalMode.Dark) MachineSignalMode.Light else MachineSignalMode.Dark
                    pixels()
                    assertTrue(visible)
                    assertTrue(background != previousBackground)
                    assertSame(identity, mounted)
                    generation++
                    page = WebSessionState(requestedContent = requested, loading = true)
                    pending()
                    assertNotSame(identity, mounted)
                } finally {
                    scene.close()
                }
            }
        }
    }
}
