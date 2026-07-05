package dev.shibasis.reaktor.core.framework

import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIScreen
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow

/**
 * Implemented by an adapter that wants the app-launch callback (e.g. to configure a
 * remote-messaging transport and register for remote notifications).
 */
interface AppLaunchHandler {
    fun didFinishLaunching(application: UIApplication)
}

/**
 * Implemented by an adapter that wants iOS remote-notification (APNs) lifecycle
 * callbacks — the notification transport records the device token here.
 */
interface RemoteNotificationHandler {
    fun didRegisterForRemoteNotifications(deviceToken: NSData)
    fun didFailToRegisterForRemoteNotifications(error: NSError)
}

/**
 * Implemented by an adapter that wants to handle inbound URLs (OAuth callbacks,
 * deep links). Return `true` if the URL was consumed.
 */
interface UrlHandler {
    fun handleUrl(url: NSURL): Boolean
}

/**
 * Reusable `UIApplicationDelegate` logic — the iOS analog of [BaseActivity]'s adapter
 * plumbing, so app controllers stay minimal.
 *
 * Kotlin/Native forbids non-final subclasses of Objective-C classes, so this is a
 * **composition** helper rather than a base class: your `final` delegate conforms to
 * `UIApplicationDelegateProtocol` directly and forwards each callback here. This helper
 * owns the window and fans every callback out to the [handlers] implementing the matching
 * interface ([AppLaunchHandler], [RemoteNotificationHandler], [UrlHandler]).
 *
 * ```kotlin
 * class MyApp : UIResponder, UIApplicationDelegateProtocol {
 *     companion object : UIResponderMeta(), UIApplicationDelegateProtocolMeta
 *     @OverrideInit constructor() : super()
 *
 *     private val reaktor = ReaktorAppDelegate({ MyViewController() }) {
 *         listOfNotNull(RemoteMessaging, Feature.Auth as? UrlHandler)
 *     }
 *
 *     override fun window() = reaktor.window
 *     override fun setWindow(window: UIWindow?) { reaktor.window = window }
 *     override fun application(application: UIApplication, didFinishLaunchingWithOptions: Map<Any?, *>?) =
 *         reaktor.didFinishLaunching(application)
 *     // …forward didRegister / didFail / openURL likewise
 * }
 * ```
 */
class ReaktorAppDelegate(
    private val rootViewController: () -> UIViewController,
    private val handlers: () -> List<Any> = { emptyList() },
) {
    var window: UIWindow? = null

    private var resolvedHandlers: List<Any> = emptyList()

    private inline fun <reified T> each(block: (T) -> Unit) {
        resolvedHandlers.forEach { if (it is T) block(it) }
    }

    fun didFinishLaunching(application: UIApplication): Boolean {
        val root = rootViewController()
        window = UIWindow(frame = UIScreen.mainScreen.bounds).apply {
            rootViewController = root
            makeKeyAndVisible()
        }
        resolvedHandlers = handlers()
        each<AppLaunchHandler> { it.didFinishLaunching(application) }
        return true
    }

    fun didRegisterForRemoteNotifications(deviceToken: NSData) =
        each<RemoteNotificationHandler> { it.didRegisterForRemoteNotifications(deviceToken) }

    fun didFailToRegisterForRemoteNotifications(error: NSError) =
        each<RemoteNotificationHandler> { it.didFailToRegisterForRemoteNotifications(error) }

    fun handleUrl(url: NSURL): Boolean {
        var handled = false
        each<UrlHandler> { if (!handled) handled = it.handleUrl(url) }
        return handled
    }
}
