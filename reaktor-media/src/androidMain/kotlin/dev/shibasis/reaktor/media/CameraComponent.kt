package dev.shibasis.reaktor.media

import androidx.camera.core.*
import androidx.camera.view.PreviewView
import dev.shibasis.reaktor.core.adapters.Permission
import dev.shibasis.reaktor.core.adapters.PermissionAdapter
import dev.shibasis.reaktor.core.adapters.StorageAdapter
import dev.shibasis.reaktor.core.framework.Async
import dev.shibasis.reaktor.core.framework.Component
import dev.shibasis.reaktor.media.camera.CameraAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine

import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// This is unsafe as a sn
// Decouple analyser logic from here, and allow multiple analysers.
/*
Component is supposed to be Platform-Agnostic and should use adapters to interact with the platform.
1. Move CameraX logic into adapter
2. Capability Based Interface
3. Decouple Analyser logic
4. Unsafe singleton
5. Needs to be Lifecycle Aware
*/

class CameraComponent(
    private val cameraAdapter: CameraAdapter<*>,
    private val permissionAdapter: PermissionAdapter<*>,
    val storageAdapter: StorageAdapter<*>
): Component {
    private var imageCapture: ImageCapture? = null

    // better lifecycle awareness
    suspend fun start(previewView: PreviewView) {
        if (permissionAdapter.request(Permission.CAMERA)) {
            imageCapture = ImageCapture.Builder().build()

//            val imageAnalyser = analyzerComponent.buildAnalyser()
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            cameraAdapter.invokeSuspend {
//                val camera = cameraAdapter.getCamera()
//                val preview = Preview.Builder().build()
//                preview.setSurfaceProvider(previewView.surfaceProvider)
//                camera.unbindAll()
//                camera.bindToLifecycle(this, cameraSelector, imageCapture, preview)
            }
        }
    }

    // TODO compile error here because of not implementing the interface
    // Introduce KMM file abstraction (okio), then fix storage adapter, then fix camera adapter
//    fun defaultJPEG() = File(
//        storageAdapter.getHomeDirectory(),
//        storageAdapter.timeStampedFileName("jpg")
//    )
//
//
//    suspend fun savePicture(photoFile: File = defaultJPEG()): File = suspendCancellableCoroutine {

    suspend fun savePicture(photoFile: File = File("")): File = suspendCancellableCoroutine {
        when(imageCapture) {
            null -> it.resumeWithException(Error("Null Image"))
            else -> {
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                imageCapture!!.takePicture(outputOptions,
                    Dispatchers.Async.asExecutor(),
                    object: ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            it.resumeWithException(exc)
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            if (output.savedUri?.path == null) it.resumeWithException(Error("Null Image"))
                            val file = File(output.savedUri!!.path!!)
                            it.resume(file)
                        }
                    })
            }
        }
    }
}
