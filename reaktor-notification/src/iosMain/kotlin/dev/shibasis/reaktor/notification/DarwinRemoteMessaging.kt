package dev.shibasis.reaktor.notification

import co.touchlab.kermit.Logger
import cocoapods.FirebaseMessaging.FIRMessaging
import cocoapods.FirebaseMessaging.FIRMessagingDelegateProtocol
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.apps
import dev.gitlive.firebase.initialize
import dev.shibasis.reaktor.core.framework.AppLaunchHandler
import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.core.framework.RemoteNotificationHandler
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.UIKit.UIApplication
import platform.UIKit.registerForRemoteNotifications
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * Firebase Cloud Messaging + APNs transport for iOS, owned by reaktor-notification.
 *
 * This is the plumbing that used to live in the app's `UIApplicationDelegate`: it owns
 * the [UNUserNotificationCenter] delegate and the [FIRMessaging] delegate, configures
 * Firebase, registers for remote notifications, and feeds tokens/taps/foreground
 * presentations into the active [IosNotificationsClient] via [IosNotificationsRuntime].
 *
 * Wire it up by listing it in a `ReaktorAppDelegate`'s `handlers()` — it implements
 * [AppLaunchHandler] (configure on launch) and [RemoteNotificationHandler] (APNs token).
 * Nothing app-specific lives here.
 */
object DarwinRemoteMessaging : AppLaunchHandler, RemoteNotificationHandler {
    private var configured = false
    private var started = false

    private val notificationCenterDelegate =
        object : NSObject(), UNUserNotificationCenterDelegateProtocol {
            override fun userNotificationCenter(
                center: UNUserNotificationCenter,
                openSettingsForNotification: UNNotification?
            ) {
                Logger.i { "Notification settings opened" }
            }

            override fun userNotificationCenter(
                center: UNUserNotificationCenter,
                didReceiveNotificationResponse: UNNotificationResponse,
                withCompletionHandler: () -> Unit
            ) {
                IosNotificationsRuntime.current()?.didReceive(didReceiveNotificationResponse)
                withCompletionHandler()
            }

            override fun userNotificationCenter(
                center: UNUserNotificationCenter,
                willPresentNotification: UNNotification,
                withCompletionHandler: (UNNotificationPresentationOptions) -> Unit
            ) {
                // Call the completion handler exactly once with the runtime's policy
                // (the previous in-app delegate called it twice — undefined behaviour).
                val options = IosNotificationsRuntime.current()?.willPresent(willPresentNotification)
                    ?: (UNNotificationPresentationOptionBanner or
                        UNNotificationPresentationOptionSound or
                        UNNotificationPresentationOptionList)
                withCompletionHandler(options)
            }
        }

    private val messagingDelegate =
        object : NSObject(), FIRMessagingDelegateProtocol {
            override fun messaging(
                messaging: FIRMessaging,
                didReceiveRegistrationToken: String?
            ) {
                IosNotificationsRuntime.current()?.recordFcmToken(didReceiveRegistrationToken)
                if (didReceiveRegistrationToken != null) {
                    Logger.i { "FCM registration token refreshed (${didReceiveRegistrationToken.length} chars)" }
                } else {
                    Logger.w { "FCM registration token was null." }
                }
            }
        }

    /** [AppLaunchHandler]: configure Firebase + the messaging transport at launch. */
    override fun didFinishLaunching(application: UIApplication) {
        if (Firebase.apps().isEmpty()) {
            Firebase.initialize()
        }
        configure()
        start()
    }

    private fun configure() {
        if (configured) return
        UNUserNotificationCenter.currentNotificationCenter().delegate = notificationCenterDelegate
        val messaging = FIRMessaging.messaging()
        messaging.delegate = messagingDelegate
        messaging.autoInitEnabled = false
        IosNotificationsRuntime.installRemoteTransportStarter {
            Dispatch.Main.launch { start() }
        }
        configured = true
    }

    private fun start() {
        if (started) return
        UNUserNotificationCenter.currentNotificationCenter().delegate = notificationCenterDelegate
        val messaging = FIRMessaging.messaging()
        messaging.delegate = messagingDelegate
        messaging.autoInitEnabled = true
        started = true
        UIApplication.sharedApplication.registerForRemoteNotifications()
    }

    /** [RemoteNotificationHandler]: APNs registration succeeded. */
    override fun didRegisterForRemoteNotifications(deviceToken: NSData) {
        Logger.i { "APNs device token received (${deviceToken.length} bytes)" }
        IosNotificationsRuntime.current()?.recordApnsToken(deviceToken)
        val messaging = FIRMessaging.messaging()
        messaging.delegate = messagingDelegate
        started = true
        messaging.APNSToken = deviceToken
        messaging.tokenWithCompletion { token, error ->
            when {
                error != null -> Logger.e(error.localizedDescription) { "Failed to fetch FCM token" }
                token != null -> {
                    IosNotificationsRuntime.current()?.recordFcmToken(token)
                    Logger.i { "FCM token available (${token.length} chars)" }
                }
                else -> {
                    IosNotificationsRuntime.current()?.recordFcmToken(null)
                    Logger.w { "FCM token fetch returned null token and null error" }
                }
            }
        }
    }

    /** [RemoteNotificationHandler]: APNs registration failed. */
    override fun didFailToRegisterForRemoteNotifications(error: NSError) {
        Logger.e(error.localizedDescription) { "Failed to register for APNs" }
    }
}
