package dev.shibasis.reaktor.location

import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.*
import platform.Foundation.NSError
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DarwinLocationAdapter(
    uiViewController: UIViewController
) : LocationAdapter<UIViewController>(uiViewController) {

    private val manager = CLLocationManager().apply {
        desiredAccuracy = kCLLocationAccuracyBest
    }

    override suspend fun getLocation(): Location = suspendCancellableCoroutine { cont ->
        ensurePermission { granted ->
            if (!granted) {
                cont.resumeWithException(Error("Location permission denied"))
                return@ensurePermission
            }

            // Configure delegate on the fly so each request is isolated
            val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {

                override fun locationManager(
                    manager: CLLocationManager,
                    didUpdateLocations: List<*>
                ) {
                    val loc = (didUpdateLocations.last() as CLLocation).coordinate
                    manager.stopUpdatingLocation()
                    loc.useContents {
                        cont.resume(Location(longitude, latitude))
                    }
                }

                override fun locationManager(
                    manager: CLLocationManager,
                    didFailWithError: NSError
                ) {
                    manager.stopUpdatingLocation()
                    cont.resumeWithException(
                        Error(didFailWithError.localizedDescription ?: "Unknown CoreLocation error")
                    )
                }
            }

            manager.delegate = delegate
            manager.startUpdatingLocation()

            cont.invokeOnCancellation { manager.stopUpdatingLocation() }
        }
    }

    /* ---------- private helpers ---------- */

    private fun ensurePermission(callback: (Boolean) -> Unit) {
        val status = CLLocationManager.authorizationStatus()
        when (status) {
            kCLAuthorizationStatusAuthorizedAlways,
            kCLAuthorizationStatusAuthorizedWhenInUse -> callback(true)

            kCLAuthorizationStatusDenied,
            kCLAuthorizationStatusRestricted           -> callback(false)

            kCLAuthorizationStatusNotDetermined        -> {
                // Request asynchronously and forward the result
                PermissionDelegate.once(callback).also { p ->
                    manager.delegate = p
                    manager.requestWhenInUseAuthorization()
                }
            }

            else -> callback(false)
        }
    }

    /** Minimal one-shot delegate for permission result */
    private class PermissionDelegate(
        private val forward: (Boolean) -> Unit
    ) : NSObject(), CLLocationManagerDelegateProtocol {

        override fun locationManager(
            manager: CLLocationManager,
            didChangeAuthorizationStatus: CLAuthorizationStatus
        ) {
            if (didChangeAuthorizationStatus != kCLAuthorizationStatusNotDetermined) {
                forward(
                    didChangeAuthorizationStatus == kCLAuthorizationStatusAuthorizedAlways ||
                            didChangeAuthorizationStatus == kCLAuthorizationStatusAuthorizedWhenInUse
                )
            }
        }

        companion object {
            fun once(cb: (Boolean) -> Unit) = PermissionDelegate(cb)
        }
    }
}
