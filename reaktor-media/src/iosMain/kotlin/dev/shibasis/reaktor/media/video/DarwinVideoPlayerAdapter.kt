package dev.shibasis.reaktor.media.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitViewController
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.media.audio.AudioRecorder
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVKit.AVPlayerViewController
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile
import platform.UniformTypeIdentifiers.UTType

class DarwinVideoPlayerAdapter : VideoPlayerAdapter<Unit>(Unit) {
    @Composable
    override fun Render(source: VideoSource, modifier: Modifier) {
        val url by produceState<NSURL?>(null, source) {
            value = withContext(Dispatchers.IO) { source.url() }
        }

        Box(modifier.background(Color.Black)) {
            url?.let { target ->
                val player = remember(target) { AVPlayer(uRL = target) }
                DisposableEffect(player) {
                    playbackSession()
                    player.play()
                    onDispose { player.pause() }
                }
                UIKitViewController(
                    factory = { AVPlayerViewController().apply { allowsPictureInPicturePlayback = false } },
                    modifier = Modifier.fillMaxSize(),
                    update = { it.player = player },
                    onRelease = { it.player = null },
                    properties = UIKitInteropProperties(isNativeAccessibilityEnabled = true),
                )
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun playbackSession() {
    if (Feature.AudioRecorder?.recording?.value == true) return
    val session = AVAudioSession.sharedInstance()
    session.setCategory(AVAudioSessionCategoryPlayback, error = null)
    session.setActive(true, error = null)
}

private fun VideoSource.url(): NSURL? = when (this) {
    is VideoSource.Remote -> NSURL.URLWithString(url)
    is VideoSource.Local -> stage()
}

@OptIn(ExperimentalForeignApi::class)
private fun VideoSource.Local.stage(): NSURL? {
    if (bytes.isEmpty()) return null
    val directory = NSTemporaryDirectory() + STAGING_DIRECTORY
    val extension = UTType.typeWithMIMEType(mimeType)?.preferredFilenameExtension ?: "mp4"
    val path = "$directory/${bytes.sha256()}.$extension"
    val files = NSFileManager.defaultManager
    if (!files.fileExistsAtPath(path)) {
        files.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        if (!data.writeToFile(path, atomically = true)) return null
    }
    return NSURL.fileURLWithPath(path)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)
private fun ByteArray.sha256(): String {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    usePinned { input ->
        digest.usePinned { output ->
            CC_SHA256(input.addressOf(0), size.toUInt(), output.addressOf(0))
        }
    }
    return digest.joinToString("") { it.toString(16).padStart(2, '0') }
}

private const val STAGING_DIRECTORY = "reaktor-video"
