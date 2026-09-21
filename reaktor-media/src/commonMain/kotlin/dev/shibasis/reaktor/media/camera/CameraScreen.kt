package dev.shibasis.reaktor.media.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.media.gallery.MediaPick
import kotlinx.coroutines.launch

/**
 * Fullscreen viewfinder: preview, shutter, flip, close. Hands the shot to [onCapture] as a
 * [MediaPick] — the same shape the gallery returns — and never uploads or stores anything itself.
 */
@Composable
fun CameraScreen(
    onCapture: (MediaPick) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    camera: CameraAdapter<*>? = Feature.Camera,
) {
    if (camera == null) {
        CameraUnavailable(onClose, modifier)
        return
    }

    var isCapturing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The device stays bound only while this screen is on screen. Teardown runs on an app-level
    // scope because rememberCoroutineScope's is already cancelled by the time onDispose fires.
    DisposableEffect(camera) {
        onDispose { Dispatch.IO.launch { camera.stop() } }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        camera.Render(Modifier.fillMaxSize())

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close camera", tint = Color.White)
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 40.dp),
        ) {
            ShutterButton(
                enabled = !isCapturing,
                modifier = Modifier.align(Alignment.Center),
                onClick = {
                    if (isCapturing) return@ShutterButton
                    scope.launch {
                        isCapturing = true
                        try {
                            camera.capturePhoto()?.let(onCapture)
                        } finally {
                            isCapturing = false
                        }
                    }
                },
            )

            IconButton(
                onClick = { scope.launch { camera.switchCamera() } },
                enabled = !isCapturing,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp),
            ) {
                Icon(Icons.Filled.Cameraswitch, contentDescription = "Flip camera", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ShutterButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (!enabled) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.Black)
        }
    }
}

@Composable
private fun CameraUnavailable(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text("Camera unavailable", color = Color.White)
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Close camera", tint = Color.White)
        }
    }
}
