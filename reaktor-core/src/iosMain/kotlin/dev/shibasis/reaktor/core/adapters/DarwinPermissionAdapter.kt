package dev.shibasis.reaktor.core.adapters

import co.touchlab.kermit.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionCriticalAlert
import platform.UserNotifications.UNAuthorizationOptionProvisional
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume
import platform.CoreLocation.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.NSObject

typealias PermissionRequestHandler = suspend () -> PermissionResult

class DarwinPermissionAdapter(): PermissionAdapter<Unit>(Unit) {
    private val requestHandler = HashMap<String, PermissionRequestHandler>()

    init {
        addHandler(Permission.CAMERA, ::cameraPermissionHandler)
        addHandler(Permission.GALLERY, ::galleryPermissionHandler)
        addHandler(Permission.SPEECH_RECOGNITION, ::speechRecognitionHandler)
        addHandler(Permission.NOTIFICATIONS) { requestNotificationPermission().toPermissionResult() }
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

    override suspend fun getNotificationPermissionStatus(
        options: NotificationPermissionOptions,
    ): NotificationPermissionStatus =
        suspendCancellableCoroutine { continuation ->
            onMainQueue {
                UNUserNotificationCenter.currentNotificationCenter()
                    .getNotificationSettingsWithCompletionHandler { settings ->
                        val authorizationStatus = settings?.authorizationStatus
                        val state = when (authorizationStatus) {
                            UNAuthorizationStatusAuthorized -> NotificationPermissionState.Granted
                            UNAuthorizationStatusDenied -> NotificationPermissionState.Denied
                            UNAuthorizationStatusProvisional -> NotificationPermissionState.Provisional
                            UNAuthorizationStatusEphemeral -> NotificationPermissionState.Ephemeral
                            UNAuthorizationStatusNotDetermined -> NotificationPermissionState.NotDetermined
                            else -> NotificationPermissionState.Unavailable
                        }
                        if (continuation.isActive) {
                            continuation.resume(
                                NotificationPermissionStatus(
                                    platform = PermissionPlatform.Ios,
                                    state = state,
                                    canRequest = state == NotificationPermissionState.NotDetermined,
                                    appNotificationsEnabled = state == NotificationPermissionState.Granted ||
                                        state == NotificationPermissionState.Provisional ||
                                        state == NotificationPermissionState.Ephemeral,
                                    detail = "authorizationStatus=${authorizationStatus ?: "missing"}",
                                ),
                            )
                        }
                    }
            }
        }

    override suspend fun requestNotificationPermission(
        options: NotificationPermissionOptions,
    ): NotificationPermissionStatus =
        getNotificationPermissionStatus().takeUnless { it.state == NotificationPermissionState.NotDetermined }
            ?: withTimeoutOrNull(NotificationAuthorizationRequestTimeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    var nativeOptions = 0uL
                    if (options.alert) nativeOptions = nativeOptions or UNAuthorizationOptionAlert
                    if (options.badge) nativeOptions = nativeOptions or UNAuthorizationOptionBadge
                    if (options.sound) nativeOptions = nativeOptions or UNAuthorizationOptionSound
                    if (options.provisional) nativeOptions = nativeOptions or UNAuthorizationOptionProvisional
                    if (options.criticalAlert) nativeOptions = nativeOptions or UNAuthorizationOptionCriticalAlert
                    onMainQueue {
                        UNUserNotificationCenter.currentNotificationCenter()
                            .requestAuthorizationWithOptions(nativeOptions) { granted, error ->
                                if (continuation.isActive) {
                                    continuation.resume(
                                        NotificationPermissionStatus(
                                            platform = PermissionPlatform.Ios,
                                            state = when {
                                                granted -> NotificationPermissionState.Granted
                                                error != null -> NotificationPermissionState.Unavailable
                                                else -> NotificationPermissionState.Denied
                                            },
                                            canRequest = false,
                                            appNotificationsEnabled = granted,
                                            detail = error?.localizedDescription ?: "request completed",
                                        ),
                                    )
                                }
                            }
                    }
                }
            } ?: getNotificationPermissionStatus()
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

private fun onMainQueue(block: () -> Unit) {
    dispatch_async(dispatch_get_main_queue()) {
        block()
    }
}

private const val NotificationAuthorizationRequestTimeoutMillis = 10_000L
