package dev.shibasis.reaktor.core.adapters

import co.touchlab.kermit.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHPhotoLibrary
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import kotlin.coroutines.resume
import platform.CoreLocation.*
import platform.darwin.NSObject

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

    // todo build time scanning by plugin to check if these are met
    override fun logPermission(permission: String) {
        Logger.i {
            when(permission) {
                Permission.LOCATION -> "Verify NSLocationWhenInUseUsageDescription, NSLocationAlwaysAndWhenInUseUsageDescription in Info.plist and Background Location Mode Entitlement in Xcode target."
                else -> "Check prerequisites for $permission"
            }
        }
    }

    override suspend fun request(vararg permissions: String): Boolean {
        var granted = true
        for (permission in permissions) {
            logPermission(permission)
            val handler = requestHandler[permission]
            if (handler == null)
                Logger.e { "Please register handler for $permission" }

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

suspend fun locationPermissionHandler(
    always: Boolean = false
): PermissionResult = suspendCancellableCoroutine { continuation ->
    val current = CLLocationManager.authorizationStatus()
    when (current) {
        kCLAuthorizationStatusAuthorizedAlways,
        kCLAuthorizationStatusAuthorizedWhenInUse -> {
            continuation.resume(PermissionResult.Granted); return@suspendCancellableCoroutine
        }
        kCLAuthorizationStatusDenied,
        kCLAuthorizationStatusRestricted -> {
            continuation.resume(PermissionResult.Denied.Forever); return@suspendCancellableCoroutine
        }
        else -> {} // request it
    }

    val manager = CLLocationManager()
    manager.delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didChangeAuthorizationStatus: CLAuthorizationStatus) {
            mapAndFinish(didChangeAuthorizationStatus)
        }

        private fun mapAndFinish(status: CLAuthorizationStatus) {
            when (status) {
                kCLAuthorizationStatusAuthorizedAlways,
                kCLAuthorizationStatusAuthorizedWhenInUse -> continuation.resume(PermissionResult.Granted)
                kCLAuthorizationStatusDenied,
                kCLAuthorizationStatusRestricted         -> continuation.resume(PermissionResult.Denied.Forever)
                else                                     -> {} // still waiting
            }
            manager.delegate = null
        }
    }

    if (always) manager.requestAlwaysAuthorization()
    else        manager.requestWhenInUseAuthorization()

    continuation.invokeOnCancellation {
        manager.delegate = null
    }
}
