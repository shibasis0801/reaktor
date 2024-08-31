package dev.shibasis.reaktor.media.camera

import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.work.await
import dev.shibasis.reaktor.core.adapters.Permission
import dev.shibasis.reaktor.core.adapters.PermissionAdapter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidCameraAdapter(
    activity: ComponentActivity,
    val permissionAdapter: PermissionAdapter<*>
): CameraAdapter<ComponentActivity>(activity) {
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    // abstraction should have CameraSelector which maps back to platform as needed

    private var imageCapture: ImageCapture? = null
    private var previewView: PreviewView? = null
    private var preview: Preview? = null

    private var camera: ProcessCameraProvider? = null
    private val cameraMutex = Mutex()

    override suspend fun switchCamera(): CameraStart {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        return start()
    }
    suspend fun getCamera(): ProcessCameraProvider? = cameraMutex.withLock {
        if (camera == null && controller != null) {
            camera = ProcessCameraProvider.getInstance(controller!!).await()
        }
        camera
    }

    override suspend fun start(): CameraStart {
        val activity = controller ?: return CameraStart.ControllerFailure
        val camera = getCamera() ?: return CameraStart.CameraFailure
        if (!permissionAdapter.request(Permission.CAMERA)) return CameraStart.PermissionFailure

        // Default UseCases are capture and preview
        imageCapture = ImageCapture.Builder().build()
        preview = Preview.Builder().build()
        preview!!.setSurfaceProvider(previewView!!.surfaceProvider)

        camera.unbind(preview, imageCapture)
        camera.bindToLifecycle(controller!!, cameraSelector, imageCapture, preview)

        return CameraStart.Success
    }

    @Composable
    override fun Render() {
        val context = LocalContext.current
        val preview = remember { PreviewView(context) }
        previewView = preview
        LaunchedEffect(Unit) {
            start()
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
            IconButton(
                modifier = Modifier
                    .padding(16.dp)
                    .background(Color.LightGray)
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .align(Alignment.BottomCenter),
                onClick = {

                }
            ) {
                Icon(Icons.Default.Create, contentDescription = "Take Picture")
            }
        }
    }
}
