package dev.shibasis.reaktor.media.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.net.Uri
import android.view.Gravity
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
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
import kotlin.math.min

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

private class VideoFrame(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val texture = TextureView(context)
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var waiting: Uri? = null
    private var playing: Uri? = null
    private var sourceWidth = 0
    private var sourceHeight = 0

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        texture.surfaceTextureListener = this
        setOnClickListener { player?.let { if (it.isPlaying) it.pause() else it.start() } }
    }

    fun play(uri: Uri) {
        if (uri == playing) return
        playing = uri
        waiting = uri
        begin()
    }

    fun release() {
        player?.release()
        player = null
        playing = null
        waiting = null
    }

    private fun begin() {
        val target = waiting ?: return
        val output = surface ?: return
        waiting = null
        player?.release()
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                setSurface(output)
                setDataSource(context, target)
                setOnVideoSizeChangedListener { _, width, height ->
                    sourceWidth = width
                    sourceHeight = height
                    fit()
                }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }.onFailure { Logger.e(it) { "Video playback could not start" } }.getOrNull()
    }

    private fun fit() {
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0) return
        val scale = min(width / sourceWidth.toFloat(), height / sourceHeight.toFloat())
        texture.layoutParams = LayoutParams((sourceWidth * scale).toInt(), (sourceHeight * scale).toInt(), Gravity.CENTER)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { fit() }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(texture)
        begin()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        player?.release()
        player = null
        surface?.release()
        surface = null
        playing?.let { waiting = it }
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
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
