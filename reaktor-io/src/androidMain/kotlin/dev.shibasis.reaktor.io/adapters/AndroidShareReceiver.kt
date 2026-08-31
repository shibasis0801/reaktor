package dev.shibasis.reaktor.io.adapters

import android.app.Activity
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Receives `ACTION_SEND` and `ACTION_SEND_MULTIPLE`.
 *
 * Two intents reach an app that declares a share `intent-filter`, and they arrive by different
 * routes. The one that launched the process is on `Activity.getIntent()` at `onCreate`; every later
 * one comes through `onNewIntent`. An app that watches only one of the two silently loses shares,
 * so the activity is expected to call [offer] from both:
 *
 * ```
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     receiver.offer(intent)
 * }
 *
 * override fun onNewIntent(intent: Intent) {
 *     super.onNewIntent(intent)
 *     setIntent(intent)
 *     receiver.offer(intent)
 * }
 * ```
 *
 * The launch intent is replayed to the first collector, because the UI that collects it is not
 * composed yet when `onCreate` runs. It is replayed once and then cleared: a share redelivered on
 * every configuration change would look to the app like the user sharing the same thing repeatedly.
 */
class AndroidShareReceiver(activity: Activity) : ShareReceiver<Activity>(activity) {

    private val shares = MutableSharedFlow<ReceivedShare>(
        // One slot of replay, for the cold-start intent that lands before anything is collecting.
        replay = 1,
        extraBufferCapacity = 8,
    )

    override val incoming: Flow<ReceivedShare> = shares.asSharedFlow()

    /** Delivered ids, so the same intent is never emitted twice across a configuration change. */
    private val seen = mutableSetOf<String>()

    fun offer(intent: Intent?): Boolean {
        val share = intent?.toReceivedShare() ?: return false

        val fingerprint = share.fingerprint()
        if (!seen.add(fingerprint)) return false

        return shares.tryEmit(share)
    }

    private fun Intent.toReceivedShare(): ReceivedShare? {
        val mime = type ?: return null

        return when (action) {
            Intent.ACTION_SEND -> ReceivedShare(
                mime = mime,
                text = getStringExtra(Intent.EXTRA_TEXT),
                fileUris = listOfNotNull(streamExtra()?.toString()),
                sourceApp = referrerPackage(),
                title = getStringExtra(Intent.EXTRA_SUBJECT) ?: getStringExtra(Intent.EXTRA_TITLE),
            )

            Intent.ACTION_SEND_MULTIPLE -> ReceivedShare(
                mime = mime,
                text = getStringExtra(Intent.EXTRA_TEXT),
                fileUris = streamExtras().map { it.toString() },
                sourceApp = referrerPackage(),
                title = getStringExtra(Intent.EXTRA_SUBJECT) ?: getStringExtra(Intent.EXTRA_TITLE),
            )

            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): android.net.Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtras(): List<android.net.Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }.orEmpty()

    private fun referrerPackage(): String? = controller?.callingPackage ?: controller?.referrer?.host

    /**
     * Identity of a share, for the redelivery check.
     *
     * Content and uris rather than the intent object, because Android hands back an equal-but-not-
     * identical `Intent` after a configuration change.
     */
    private fun ReceivedShare.fingerprint(): String =
        listOf(mime, text.orEmpty(), fileUris.joinToString(","), title.orEmpty()).joinToString("|")
}
