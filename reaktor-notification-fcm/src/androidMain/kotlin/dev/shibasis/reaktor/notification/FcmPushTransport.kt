package dev.shibasis.reaktor.notification

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/**
 * Firebase Cloud Messaging as the remote push transport for [AndroidNotificationsClient].
 *
 * Hand it to the client through its config:
 *
 * ```
 * Notifications = AndroidNotificationsClient(
 *     context,
 *     AndroidNotificationsConfig(pushTransport = FcmPushTransport),
 * )
 * ```
 *
 * Without this the client has no transport, reports no device token, and still schedules and
 * renders local notifications exactly as before.
 */
object FcmPushTransport : AndroidPushTransport {
    override val providerId: String = "fcm"

    override suspend fun token(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                continuation.resume(if (task.isSuccessful) task.result else null)
            }
    }
}

/**
 * The FCM entry point, which an app subclasses and declares in its own manifest.
 *
 * It exists as a class rather than a lambda because Android instantiates it by name from the
 * manifest, long before any application code has run.
 */
open class ReaktorFirebaseMessagingService : FirebaseMessagingService() {
    open val notificationsConfig: AndroidNotificationsConfig
        get() = AndroidNotificationsConfig(pushTransport = FcmPushTransport)

    override fun onMessageReceived(message: RemoteMessage) {
        val client = AndroidNotificationsRuntime.ensure(this, notificationsConfig)
        runBlocking { withTimeoutOrNull(ReceiveBudget) { client.receiveRemoteMessage(message.data) } }
    }

    override fun onNewToken(token: String) {
        AndroidNotificationsRuntime.ensure(this, notificationsConfig).recordNewToken(token)
    }

    private companion object {
        val ReceiveBudget = 9.seconds
    }
}
