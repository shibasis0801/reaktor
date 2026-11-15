package dev.shibasis.reaktor.unlock

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.LocalAuthentication.LABiometryTypeFaceID
import platform.LocalAuthentication.LABiometryTypeTouchID
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIWindow
import platform.UIKit.systemBackgroundColor
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * The iPhone's own unlock: Face ID or Touch ID, falling back to the passcode
 * (`deviceOwnerAuthentication`).
 *
 * The app must carry `NSFaceIDUsageDescription` in its Info.plist: iOS ends an app that asks for
 * Face ID without one. This module can't add it; it is the app's sentence to write.
 */
@OptIn(ExperimentalForeignApi::class)
class DarwinDeviceUnlock : DeviceUnlock {
    private var cover: UIView? = null
    private var watching: List<NSObjectProtocol> = emptyList()

    override fun kind(): UnlockKind {
        val context = LAContext()
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)) return UnlockKind.None
        // Set by canEvaluatePolicy, and none when nothing is enrolled: the passcode is what's left.
        return when (context.biometryType) {
            LABiometryTypeFaceID -> UnlockKind.Face
            LABiometryTypeTouchID -> UnlockKind.Fingerprint
            else -> UnlockKind.Credential
        }
    }

    /** iOS writes the prompt's title itself; [reason] is the line under it. */
    override suspend fun ask(title: String, reason: String): Unlock = suspendCancellableCoroutine { waiter ->
        val context = LAContext()
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)) {
            waiter.resume(Unlock.Unavailable)
            return@suspendCancellableCoroutine
        }
        waiter.invokeOnCancellation { context.invalidate() }
        context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, localizedReason = reason) { ok, error ->
            val result = when {
                ok -> Unlock.Unlocked
                error == null -> Unlock.Cancelled
                else -> when (error.code) {
                    LAErrorUserCancel, LAErrorSystemCancel, LAErrorAppCancel -> Unlock.Cancelled
                    LAErrorPasscodeNotSet -> Unlock.Unavailable
                    else -> Unlock.Failed(error.localizedDescription)
                }
            }
            // The reply comes on a queue of the system's own; the app's state lives on the main one.
            dispatch_async(dispatch_get_main_queue()) { if (waiter.isActive) waiter.resume(result) }
        }
    }

    /**
     * iOS photographs the app as it leaves the screen and shows that in the app switcher. While
     * [hidden], a plain cover goes over the window just before, and comes off when the app is
     * active again.
     */
    override fun hideContents(hidden: Boolean) {
        if (hidden == watching.isNotEmpty()) return
        val center = NSNotificationCenter.defaultCenter
        if (hidden) {
            watching = listOf(
                center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) { _ -> coverUp() },
                center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ -> uncover() },
            )
        } else {
            watching.forEach { center.removeObserver(it) }
            watching = emptyList()
            uncover()
        }
    }

    private fun coverUp() {
        if (cover != null) return
        val application = UIApplication.sharedApplication
        val window = application.keyWindow ?: application.windows.firstOrNull() as? UIWindow ?: return
        cover = UIView(frame = window.bounds).apply {
            backgroundColor = UIColor.systemBackgroundColor
            autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
            window.addSubview(this)
        }
    }

    private fun uncover() {
        cover?.removeFromSuperview()
        cover = null
    }
}
