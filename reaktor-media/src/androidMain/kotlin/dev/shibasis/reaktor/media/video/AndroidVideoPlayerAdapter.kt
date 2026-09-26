package dev.shibasis.reaktor.media.video

import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class AndroidVideoPlayerAdapter(context: Context) : VideoPlayerAdapter<Context>(context) {
    @Composable
    override fun Render(source: VideoSource, modifier: Modifier) {
        val context = LocalContext.current
        val uri by produceState<Uri?>(null, source) {
            value = withContext(Dispatchers.IO) {
                runCatching { source.uri(File((controller ?: context).cacheDir, STAGING_DIRECTORY)) }
                    .onFailure { Logger.e(it) { "Video could not be staged for playback" } }
                    .getOrNull()
            }
        }

        Box(modifier.background(Color.Black)) {
            uri?.let { target ->
                AndroidView(
                    factory = ::VideoFrame,
                    modifier = Modifier.fillMaxSize(),
                    update = { it.play(target) },
                    onRelease = { it.release() },
                )
            }
        }
    }
}

private class VideoFrame(context: Context) : FrameLayout(context) {
    private val video = VideoView(context)
    private val controls = MediaController(context)
    private var playing: Uri? = null

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        video.setMediaController(controls)
        video.setOnPreparedListener { video.start() }
    }

    fun play(uri: Uri) {
        if (uri == playing) return
        playing = uri
        video.setVideoURI(uri)
    }

    fun release() {
        controls.hide()
        video.stopPlayback()
        playing = null
    }
}

private fun VideoSource.uri(directory: File): Uri = when (this) {
    is VideoSource.Remote -> Uri.parse(url)
    is VideoSource.Local -> Uri.fromFile(stage(directory))
}

private fun VideoSource.Local.stage(directory: File): File {
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "mp4"
    val file = File(directory, "${bytes.sha256()}.$extension")
    if (file.exists()) return file
    directory.mkdirs()
    val partial = File.createTempFile(file.name, ".partial", directory)
    partial.writeBytes(bytes)
    if (!partial.renameTo(file)) partial.delete()
    return file
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

private const val STAGING_DIRECTORY = "reaktor-video"
