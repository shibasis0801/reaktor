package dev.shibasis.reaktor.media.camera

import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.work.await
import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.adapters.Permission
import dev.shibasis.reaktor.core.adapters.PermissionAdapter
import dev.shibasis.reaktor.core.framework.Async
import dev.shibasis.reaktor.media.gallery.MediaPick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

class AndroidCameraAdapter(
    activity: ComponentActivity,
    private val permissionAdapter: PermissionAdapter<*>,
) : CameraAdapter<ComponentActivity>(activity) {

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var previewView: PreviewView? = null
    private val bindLock = Mutex()

    private suspend fun provider(activity: ComponentActivity): ProcessCameraProvider? =
        provider ?: runCatching { ProcessCameraProvider.getInstance(activity).await() }
            .onFailure { Logger.e(it) { "CameraX provider unavailable" } }
            .getOrNull()
            ?.also { provider = it }

    override suspend fun start(facing: CameraFacing): CameraStart {
        val activity = controller ?: return CameraStart.ControllerFailure
        if (!permissionAdapter.request(Permission.CAMERA)) return CameraStart.PermissionFailure
        val provider = provider(activity) ?: return CameraStart.CameraFailure

        return bindLock.withLock {
            // Render() populates this; start() before the first composition has no surface to bind.
            val surface = previewView ?: return@withLock CameraStart.CameraFailure
            val selector = when (facing) {
                CameraFacing.Back -> CameraSelector.DEFAULT_BACK_CAMERA
                CameraFacing.Front -> CameraSelector.DEFAULT_FRONT_CAMERA
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(surface.display?.rotation ?: Surface.ROTATION_0)
                .build()
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(surface.surfaceProvider)
            }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(activity, selector, capture, preview)
            }.fold(
                onSuccess = {
                    imageCapture = capture
                    setFacing(facing)
                    CameraStart.Success
                },
                onFailure = { error ->
                    Logger.e(error) { "CameraX bind failed for $facing" }
                    imageCapture = null
                    CameraStart.CameraFailure
                },
            )
        }
    }

    override suspend fun capturePhoto(): MediaPick? {
        val capture = imageCapture ?: run {
            Logger.e { "capturePhoto before a successful start()" }
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                Dispatchers.Async.asExecutor(),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bytes = image.use { it.jpegBytes() }
                        if (continuation.isActive) {
                            continuation.resume(MediaPick(bytes = bytes, mimeType = JPEG))
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Logger.e(exception) { "CameraX capture failed" }
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )
        }
    }

    override suspend fun stop() {
        bindLock.withLock {
            runCatching { provider?.unbindAll() }
                .onFailure { Logger.w(it) { "CameraX unbind failed" } }
            imageCapture = null
        }
    }

    @Composable
    override fun Render(modifier: Modifier) {
        val context = LocalContext.current
        val surface = remember { PreviewView(context) }
        val active by facing.collectAsState()

        DisposableEffect(surface) {
            previewView = surface
            onDispose { previewView = null }
        }

        LaunchedEffect(surface, active) { start(active) }

        AndroidView(factory = { surface }, modifier = modifier)
    }
}

private const val JPEG = "image/jpeg"

/**
 * OnImageCapturedCallback hands back a single-plane JPEG whose EXIF already carries sensor
 * rotation and front-camera mirroring, so the bytes need no reorientation here.
 */
private fun ImageProxy.jpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    return ByteArray(buffer.remaining()).also(buffer::get)
}
