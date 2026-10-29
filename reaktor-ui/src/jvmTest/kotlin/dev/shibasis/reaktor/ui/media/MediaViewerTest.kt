package dev.shibasis.reaktor.ui.media

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.ui.machinesignal.surface.MediaViewer
import dev.shibasis.reaktor.web.WebRuntime
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class MediaViewerTest {
    private fun view(mime: String, bytes: ByteArray, check: androidx.compose.ui.test.ComposeUiTest.() -> Unit) {
        val dependencies = org.koin.dsl.koinApplication {}
        val graph = Graph(dependencyAdapter = dev.shibasis.reaktor.graph.di.KoinDependencyAdapter(dependencies), label = "Media render test")
        try {
            val runtime = graph.Node(::WebRuntime)
            runComposeUiTest {
                setContent { MediaViewer(runtime, MediaSource.Bytes(bytes), mime, Modifier.size(700.dp, 400.dp)) }
                check()
            }
        } finally { graph.close(); dependencies.close() }
    }

    @Test fun textUsesTheReadOnlyCodePlane() = view("text/plain", "hello media".encodeToByteArray()) {
        onNodeWithTag("media-viewer-code").assertExists()
        onNode(hasText("hello media", substring = true)).assertExists()
        onNodeWithTag("media-viewer-info").assertTextContains("11 bytes", substring = true)
    }

    @Test fun jsonUsesFoldableTreeRows() = view("application/json", "{\"child\":{\"name\":\"nested\"}}".encodeToByteArray()) {
        onNode(hasText("child: {1 fields}")).assertExists()
        onNode(hasText("name: \"nested\"")).assertDoesNotExist()
        onNodeWithTag("media-viewer/row/$/child").performClick().performKeyInput { pressKey(Key.DirectionRight) }
        onNode(hasText("name: \"nested\"")).assertExists()
    }

    @Test fun csvRendersHeadersAndCells() = view("text/csv", "name,bytes\nphoto.jpg,2048".encodeToByteArray()) {
        onNode(hasText("name")).assertExists()
        onNode(hasText("photo.jpg")).assertExists()
        onNode(hasText("2048")).assertExists()
    }

    @Test fun imageControlsChangeTheZoomAndKeepItsPixelSize() {
        val encoded = ByteArrayOutputStream().apply { ImageIO.write(BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", this) }.toByteArray()
        view("image/png", encoded) {
            onNodeWithTag("media-viewer-image-size").assertTextContains("4 × 3 px", substring = true)
            onNodeWithTag("media-viewer-zoom-in").performClick()
            onNodeWithTag("media-viewer-image-size").assertTextContains("125%", substring = true)
            onNodeWithTag("media-viewer-fit").performClick()
            onNodeWithTag("media-viewer-image-size").assertTextContains("100% of fit", substring = true)
        }
    }

    @Test fun unknownTypesAndDecodeFailuresHaveVisibleStates() {
        view("application/x-unknown", byteArrayOf(65)) {
            onNodeWithTag("media-viewer-unsupported").assertExists()
            onNode(hasText("00000000", substring = true)).assertExists()
        }
        view("image/png", "bad image".encodeToByteArray()) { onNodeWithTag("media-viewer-error").assertTextContains("Could not decode image", substring = true) }
    }
}
