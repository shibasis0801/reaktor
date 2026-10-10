package dev.shibasis.reaktor.notification

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.AppLaunchHandler
import dev.shibasis.reaktor.core.framework.AppleFirebaseRuntime
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

object DarwinRemoteMessaging : AppLaunchHandler, RemoteNotificationHandler {
    private var configured = false
    private var started = false
    private val messaging get() = AppleRemoteMessagingRuntime.current()

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

    /** [AppLaunchHandler]: configure Firebase + the messaging transport at launch. */
    override fun didFinishLaunching(application: UIApplication) {
        configure()
        start()
    }

    private fun configure() {
        if (configured) return
        UNUserNotificationCenter.currentNotificationCenter().delegate = notificationCenterDelegate
        AppleFirebaseRuntime.configure()
        messaging.autoInit(false)
        messaging.attachDelegate()
        AppleRemoteMessagingRuntime.observeToken { token -> IosNotificationsRuntime.current()?.recordFcmToken(token) }
        IosNotificationsRuntime.installRemoteTransportStarter { Dispatch.Main.launch { start() } }
        IosNotificationsRuntime.installRemoteTokenForgetter {
            messaging.deleteToken { error -> if (error != null) Logger.w { "Could not delete the FCM token: ${error.localizedDescription}" } }
        }
        configured = true
    }

    private fun start() {
        if (started) return
        UNUserNotificationCenter.currentNotificationCenter().delegate = notificationCenterDelegate
        messaging.autoInit(true)
        started = true
        UIApplication.sharedApplication.registerForRemoteNotifications()
    }

    /** [RemoteNotificationHandler]: APNs registration succeeded. */
    override fun didRegisterForRemoteNotifications(deviceToken: NSData) {
        Logger.i { "APNs device token received (${deviceToken.length} bytes)" }
        IosNotificationsRuntime.current()?.recordApnsToken(deviceToken)
        started = true
        messaging.setApnsToken(deviceToken)
        messaging.requestToken { token, error ->
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
