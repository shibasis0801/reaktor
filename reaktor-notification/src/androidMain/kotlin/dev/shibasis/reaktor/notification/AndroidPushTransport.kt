package dev.shibasis.reaktor.notification

/**
 * Where a remote push token comes from on Android.
 *
 * Remote push needs a transport, and on Android that means Firebase Cloud Messaging — which is
 * not a free dependency. Linking `firebase-messaging` merges INTERNET, WAKE_LOCK and
 * `com.google.android.c2dm.permission.RECEIVE` into the app's manifest and adds several megabytes
 * of dex. An app that only schedules a local reminder was paying all of that, and then having to
 * account for those permissions on a store listing it could not honestly justify.
 *
 * So the transport is a separate artifact — `reaktor-notification-fcm`, whose `FcmPushTransport`
 * implements this — and [AndroidNotificationsConfig.pushTransport] is the seam. With no transport
 * installed, [AndroidNotificationsClient] schedules, renders and routes local notifications
 * exactly as before; it simply reports that it has no device token.
 */
interface AndroidPushTransport {
    /** Names the provider in a [DevicePushToken]. "fcm" for Firebase Cloud Messaging. */
    val providerId: String

    /** The current device token, or null when one cannot be obtained. */
    suspend fun token(): String?
}
