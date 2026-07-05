package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.core.framework.Feature
import platform.Foundation.NSDateComponents
import platform.Foundation.NSURL
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionAuthenticationRequired
import platform.UserNotifications.UNNotificationActionOptionDestructive
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationAttachment
import platform.UserNotifications.UNNotificationPresentationOptionBadge
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTextInputNotificationAction
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.max

class IosNotificationDevHarness(
    private val client: IosNotificationsClient,
) : BaseNotificationDevHarness(client, NotificationPlatform.Ios) {
    private var lastEnvelope: NotificationEnvelope? = null
    private var token: DevicePushToken? = null
    private var apnsTokenLength: ULong? = null

    fun recordToken(token: DevicePushToken?, apnsTokenLength: ULong?) {
        this.token = token
        this.apnsTokenLength = apnsTokenLength
        recordTokenForState(token)
    }

    fun recordReceivedFromPlatform(envelope: NotificationEnvelope) {
        lastEnvelope = envelope
        recordReceived(envelope)
    }

    fun recordResponseFromPlatform(event: NotificationResponseEvent) {
        recordResponse(event)
    }

    override suspend fun injectRemoteEnvelope(): NotificationDevState {
        val envelope = NotificationEnvelope(
            id = "ios-dev-remote-${Clock.nowEpochMillis()}",
            type = "reaktor.dev.notification",
            categoryId = "messages",
            content = NotificationContent(
                title = "iOS notification test",
                body = "Synthetic APNs/FCM payload received",
                threadId = "dev",
            ),
            route = NotificationRoute.GraphAction("reaktor.notification.open", "{}"),
        )
        lastEnvelope = envelope
        return client.injectRemoteEnvelope(envelope)
    }

    override suspend fun simulateTap(): NotificationDevState {
        val envelope = lastEnvelope ?: NotificationEnvelope(
            id = "ios-dev-tap-${Clock.nowEpochMillis()}",
            type = "reaktor.dev.notification",
            categoryId = "messages",
            content = NotificationContent("iOS notification test", "Synthetic tap"),
            route = NotificationRoute.GraphAction("reaktor.notification.open", "{}"),
        )
        return client.simulateTap(envelope)
    }
}

object IosNotificationsRuntime {
    private var client: IosNotificationsClient? = null
    private var remoteTransportStarter: (() -> Unit)? = null

    fun install(client: IosNotificationsClient) {
        this.client = client
    }

    fun current(): IosNotificationsClient? = client ?: Feature.Notifications as? IosNotificationsClient

    fun installRemoteTransportStarter(starter: () -> Unit) {
        remoteTransportStarter = starter
    }

    fun startRemoteTransport() {
        remoteTransportStarter?.invoke()
    }
}

internal fun toNativeAction(action: NotificationActionSpec): UNNotificationAction {
    var options = 0uL
    if (NotificationActionOption.AuthenticationRequired in action.options) {
        options = options or UNNotificationActionOptionAuthenticationRequired
    }
    if (NotificationActionOption.Destructive in action.options) {
        options = options or UNNotificationActionOptionDestructive
    }
    if (NotificationActionOption.Foreground in action.options || action.kind == NotificationActionKind.Open) {
        options = options or UNNotificationActionOptionForeground
    }
    return if (action.kind == NotificationActionKind.TextInput) {
        UNTextInputNotificationAction.actionWithIdentifier(
            identifier = action.id,
            title = action.title,
            options = options,
            textInputButtonTitle = action.textInput?.buttonTitle ?: "Send",
            textInputPlaceholder = action.textInput?.placeholder ?: "",
        )
    } else {
        UNNotificationAction.actionWithIdentifier(
            identifier = action.id,
            title = action.title,
            options = options,
        )
    }
}

internal fun onMainQueue(block: () -> Unit) {
    dispatch_async(dispatch_get_main_queue()) {
        block()
    }
}

internal fun notificationUserInfo(request: LocalNotificationRequest): Map<Any?, *> =
    NotificationEnvelope(
        id = request.id,
        type = "local",
        categoryId = request.categoryId,
        content = request.content,
        route = request.route,
    ).toDataMap().mapKeys { entry -> entry.key as Any? }

internal fun Map<Any?, *>.toStringMap(): Map<String, String> =
    entries.associate { entry -> entry.key.toString() to entry.value.toString() }

internal fun ForegroundPresentationPolicy.toIosPresentationOptions(): UNNotificationPresentationOptions {
    var options = 0uL
    if (banner) options = options or UNNotificationPresentationOptionBanner
    if (list) options = options or UNNotificationPresentationOptionList
    if (sound) options = options or UNNotificationPresentationOptionSound
    if (badge) options = options or UNNotificationPresentationOptionBadge
    return options
}

internal fun NotificationSound.toIosSound(): UNNotificationSound? = when (this) {
    NotificationSound.Default -> UNNotificationSound.defaultSound()
    NotificationSound.Silent -> null
    is NotificationSound.Named -> UNNotificationSound.soundNamed(name)
}

internal fun LocalNotificationRequest.toIosTrigger(): platform.UserNotifications.UNNotificationTrigger? {
    if (delay.inWholeMilliseconds > 0) {
        return UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = max(1.0, delay.inWholeMilliseconds.toDouble() / 1000.0),
            repeats = false,
        )
    }
    return when (val trigger = trigger) {
        NotificationTrigger.Immediate -> null
        is NotificationTrigger.TimeInterval -> UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = max(1.0, trigger.delay.inWholeMilliseconds.toDouble() / 1000.0),
            repeats = trigger.repeats,
        )
        is NotificationTrigger.Calendar -> {
            val components = NSDateComponents().apply {
                trigger.year?.let { setYear(it.toLong()) }
                trigger.month?.let { setMonth(it.toLong()) }
                trigger.day?.let { setDay(it.toLong()) }
                trigger.hour?.let { setHour(it.toLong()) }
                trigger.minute?.let { setMinute(it.toLong()) }
                trigger.second?.let { setSecond(it.toLong()) }
            }
            UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(
                dateComponents = components,
                repeats = trigger.repeats,
            )
        }
    }
}

internal fun nativeAttachment(attachment: NotificationAttachment): UNNotificationAttachment? {
    val url = NSURL.URLWithString(attachment.url) ?: return null
    return runCatching {
        UNNotificationAttachment.attachmentWithIdentifier(
            identifier = attachment.id,
            URL = url,
            options = attachment.typeHint?.let { mapOf("UNNotificationAttachmentOptionsTypeHintKey" to it) },
            error = null,
        )
    }.getOrNull()
}
