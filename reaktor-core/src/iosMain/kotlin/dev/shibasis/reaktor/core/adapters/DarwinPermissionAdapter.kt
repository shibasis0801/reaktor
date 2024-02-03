package dev.shibasis.reaktor.core.adapters

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume


typealias PermissionRequestHandler = suspend () -> PermissionResult

class DarwinPermissionAdapter(): PermissionAdapter<Unit>(Unit) {
    private val requestHandler = HashMap<String, PermissionRequestHandler>()

    init {
        addHandler(Permission.CAMERA, ::cameraPermissionHandler)
        addHandler(Permission.GALLERY, ::galleryPermissionHandler)
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
// todo suspendcancellablecoroutine needs a timeout feature
suspend fun cameraPermissionHandler() = suspendCancellableCoroutine { continuation ->
    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) {
        if (it) {
            continuation.resume(PermissionResult.Granted)
        }
        else {
            continuation.resume(PermissionResult.Denied.Once)
        }
    }
}


suspend fun galleryPermissionHandler() = suspendCancellableCoroutine { continuation ->
    PHPhotoLibrary.requestAuthorization {
        if (it == PHAuthorizationStatusAuthorized) {
            continuation.resume(PermissionResult.Granted)
        }
        else {
            continuation.resume(PermissionResult.Denied.Once)
        }
    }
}