package dev.shibasis.reaktor.media.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.media.gallery.MediaPick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CameraFacing {
    Back,
    Front;

    fun flipped() = if (this == Back) Front else Back
}

/**
 * A camera that previews and captures. [capturePhoto] returns the same [MediaPick] the gallery
 * yields, so both feed one upload path.
 */
abstract class CameraAdapter<Controller>(
    controller: Controller,
) : Adapter<Controller>(controller) {
    enum class CameraStart {
        Success,
        ControllerFailure,
        PermissionFailure,
        CameraFailure,
    }

    private val _facing = MutableStateFlow(CameraFacing.Back)
    val facing: StateFlow<CameraFacing> = _facing.asStateFlow()

    protected fun setFacing(value: CameraFacing) {
        _facing.value = value
    }

    abstract suspend fun start(facing: CameraFacing = this.facing.value): CameraStart

    open suspend fun switchCamera(): CameraStart = start(facing.value.flipped())

    /** Null when the capture failed or the session was not running. */
    abstract suspend fun capturePhoto(): MediaPick?

    /** Release the device. Callers must invoke this when the camera surface leaves the screen. */
    abstract suspend fun stop()

    @Composable
    abstract fun Render(modifier: Modifier)
}

var Feature.Camera by CreateSlot<CameraAdapter<*>>()
