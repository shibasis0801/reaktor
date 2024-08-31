package dev.shibasis.reaktor.media.camera

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

/*
This interface should be the specs for a Camera
How to handle multiple cameras
How to switch between them
etc

The main camera implementation can switch between front and rear cameras.
But you can create multiple adapters for a platform if you wish to implement multiple views.

Study if ECS can help here (platform differences)
*/
abstract class CameraAdapter<Controller>(
    controller: Controller
): Adapter<Controller>(controller) {
    enum class CameraStart {
        Success,
        ControllerFailure,
        PermissionFailure,
        CameraFailure,
    }

    // Switching between cameras is optional
    open suspend fun switchCamera(): CameraStart { return CameraStart.Success
    }
    abstract suspend fun start(): CameraStart
    @Composable
    abstract fun Render()

    // Use kotlinx-io to read and write images
    interface FileCapability {
        fun storeFile(name: String)
    }

    interface AnalyserCapability {
        fun addAnalyser(): Boolean
    }
}

/*
The framework must be AI first for rapid prototyping with high performance.
There should be multiple guard rails in the form of Functional Tests, Security Tests, Memory Tests etc.
It should factor into account that AIs are going to be immensely more powerful.
*/

var Feature.Camera by CreateSlot<CameraAdapter<*>>()









