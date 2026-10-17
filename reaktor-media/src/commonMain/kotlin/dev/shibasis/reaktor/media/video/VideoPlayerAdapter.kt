package dev.shibasis.reaktor.media.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

sealed interface VideoSource {
    data class Remote(val url: String) : VideoSource

    class Local(val bytes: ByteArray, val mimeType: String) : VideoSource
}

abstract class VideoPlayerAdapter<Controller>(controller: Controller) : Adapter<Controller>(controller) {
    @Composable
    abstract fun Render(source: VideoSource, modifier: Modifier)
}

var Feature.VideoPlayer by CreateSlot<VideoPlayerAdapter<*>>()
