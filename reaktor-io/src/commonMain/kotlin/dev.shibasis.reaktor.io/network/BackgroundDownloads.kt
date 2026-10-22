package dev.shibasis.reaktor.io.network

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** A file to fetch in the background. [key] is the caller's own name for it and comes back in [Transfer]. */
@Serializable
data class DownloadRequest(
    val key: String,
    val url: String,
    /** Where it lands, relative to [BackgroundDownloads.directory]. Must be a plain file name. */
    val fileName: String,
    /** Shown by the system while it downloads, where the platform shows anything. */
    val title: String,
    /** False holds the download until Wi-Fi; it waits rather than fails. */
    val allowMetered: Boolean = false,
)

/** Why a download is waiting or stopped, in terms a screen can turn into words. */
enum class TransferProblem {
    /** No connection at the moment. It resumes by itself. */
    WaitingForNetwork,
    /** Only Wi-Fi was allowed, and there is none. It resumes by itself. */
    WaitingForWifi,
    /** The server said no — moved, gone, refused. */
    Server,
    /** The phone is out of space. */
    Storage,
    Unknown,
}

/** Where one download stands. */
@Serializable
sealed interface Transfer {
    val key: String

    @Serializable
    data class Queued(override val key: String) : Transfer

    @Serializable
    data class Running(override val key: String, val bytes: Long, val totalBytes: Long?) : Transfer

    /** Held by the system, not failed: it continues on its own when [problem] clears. */
    @Serializable
    data class Waiting(override val key: String, val bytes: Long, val totalBytes: Long?, val problem: TransferProblem) : Transfer

    /** On disk at [path], [bytes] long. */
    @Serializable
    data class Done(override val key: String, val path: String, val bytes: Long) : Transfer

    @Serializable
    data class Failed(override val key: String, val problem: TransferProblem, val detail: String = "") : Transfer
}

/**
 * Large files fetched by the operating system, not by the app.
 *
 * A podcast episode is 50–150 MB, and the person who asked for it has usually locked the phone and
 * walked off. A download the app runs itself dies with the app; a background task is given seconds.
 * What survives is a transfer the system owns — `DownloadManager` on Android, a background
 * `URLSession` on Apple — which keeps going after the app is killed, resumes after a dropped
 * connection where the server allows it, and follows the redirects podcast hosts wrap their audio in.
 *
 * So the model is: hand the system a URL and a file name, watch [transfers], and call [reconcile]
 * after launch to learn what finished, failed or is still going while the app was not running.
 * [transfers] holds what this process has seen; the app keeps its own record of finished files.
 *
 * Interface plus slot: a platform without one leaves `Feature.Downloads` empty.
 */
interface BackgroundDownloads {
    /** Absolute folder files land in. Kept out of backups; removed with the app. */
    val directory: String

    val transfers: StateFlow<Map<String, Transfer>>

    /** Starts [request], replacing any earlier transfer with the same key. */
    fun enqueue(request: DownloadRequest)

    /**
     * Stops a transfer and deletes what it wrote, as far as this process knows of it. A file that
     * finished in an earlier run is on the caller's own record, and is the caller's to delete.
     */
    fun remove(key: String)

    /** Learns from the system what happened while the app was not running. */
    suspend fun reconcile()
}

var Feature.Downloads by CreateSlot<BackgroundDownloads>()
