package dev.shibasis.reaktor.core.adapters

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHPhotoLibrary
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import kotlin.coroutines.resume


typealias PermissionRequestHandler = suspend () -> PermissionResult

class DarwinPermissionAdapter(): PermissionAdapter<Unit>(Unit) {
    private val requestHandler = HashMap<String, PermissionRequestHandler>()

    init {
        addHandler(Permission.CAMERA, ::cameraPermissionHandler)
        addHandler(Permission.GALLERY, ::galleryPermissionHandler)
        addHandler(Permission.SPEECH_RECOGNITION, ::speechRecognitionHandler)
    }

    fun addHandler(permission: String, handler: PermissionRequestHandler) {
        requestHandler[permission] = handler
    }

    override suspend fun request(vararg permissions: String): Boolean {
        var granted = true
        for (permission in permissions) {
            val result = requestHandler[permission]?.invoke()
            granted = granted && result == PermissionResult.Granted
        }
        return granted
    }
}


private fun Boolean.toPermissionResult() = if (this) PermissionResult.Granted else PermissionResult.Denied.Once


// todo suspendcancellablecoroutine needs a timeout feature
suspend fun cameraPermissionHandler() = suspendCancellableCoroutine { continuation ->
    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) {
        continuation.resume(it.toPermissionResult())
    }
}


suspend fun galleryPermissionHandler() = suspendCancellableCoroutine { continuation ->
    PHPhotoLibrary.requestAuthorization {
        continuation.resume((it == PHAuthorizationStatusAuthorized).toPermissionResult())
    }
}

suspend fun speechRecognitionHandler() = suspendCancellableCoroutine { continuation ->
    SFSpeechRecognizer.requestAuthorization {
        continuation.resume((it == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized).toPermissionResult())
    }
}