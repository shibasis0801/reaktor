package dev.shibasis.reaktor.io.network

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * [BackgroundDownloads] on Android's `DownloadManager`.
 *
 * The system service does the transfer: it survives the app being killed, retries after a dropped
 * connection, waits for Wi-Fi when metered data is not allowed, follows redirects, and shows its own
 * progress notification. Nothing here needs a permission beyond `INTERNET`, and files go to the
 * app's own external files folder, which needs none either and is removed with the app.
 *
 * Which system download belongs to which key is kept in a small preferences file, because the
 * system's ids are all `DownloadManager` gives back after a restart. While anything is in flight
 * the service is polled once a second; nothing is polled when nothing is.
 *
 * Takes any [Context] and keeps the application's.
 */
class AndroidBackgroundDownloads(context: Context) : BackgroundDownloads {
    private val app = context.applicationContext
    private val manager = app.getSystemService(DownloadManager::class.java)
    private val ids = app.getSharedPreferences("reaktor-downloads", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var poller: Job? = null

    override val directory: String =
        (app.getExternalFilesDir(Environment.DIRECTORY_PODCASTS) ?: File(app.filesDir, "downloads"))
            .also { it.mkdirs() }.absolutePath

    private val _transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    override val transfers: StateFlow<Map<String, Transfer>> = _transfers.asStateFlow()

    override fun enqueue(request: DownloadRequest) {
        require('/' !in request.fileName) { "fileName must be a plain file name: ${request.fileName}" }
        remove(request.key)
        val target = File(directory, request.fileName)
        target.delete()
        val id = manager.enqueue(
            DownloadManager.Request(Uri.parse(request.url))
                .setTitle(request.title)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(target))
                .setAllowedOverMetered(request.allowMetered)
                .setAllowedOverRoaming(false),
        )
        ids.edit().putLong(request.key, id).apply()
        _transfers.update { it + (request.key to Transfer.Queued(request.key)) }
        poll()
    }

    override fun remove(key: String) {
        val id = ids.getLong(key, -1L)
        // Removing a download deletes its file too, finished or not.
        if (id >= 0) manager.remove(id)
        ids.edit().remove(key).apply()
        _transfers.update { it - key }
    }

    override suspend fun reconcile() {
        refresh()
        poll()
    }

    private fun poll() {
        if (poller?.isActive == true) return
        poller = scope.launch {
            while (isActive) {
                val inFlight = runCatching { refresh() }.getOrDefault(false)
                if (!inFlight) break
                delay(POLL_MS)
            }
        }
    }

    /** Reads every tracked download's state. Returns whether any is still in flight. */
    private fun refresh(): Boolean {
        val tracked = ids.all.mapNotNull { (key, id) -> (id as? Long)?.let { key to it } }.toMap()
        if (tracked.isEmpty()) {
            _transfers.value = emptyMap()
            return false
        }
        val byId = tracked.entries.associate { (key, id) -> id to key }
        val seen = mutableMapOf<String, Transfer>()
        manager.query(DownloadManager.Query().setFilterById(*tracked.values.toLongArray()))?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.long(DownloadManager.COLUMN_ID)
                val key = byId[id] ?: continue
                seen[key] = cursor.transfer(key)
            }
        }
        // The system no longer knows a download it once had: the user cleared it, or storage was
        // wiped. Forget the id; the caller's own record decides what that means.
        tracked.keys.filter { it !in seen }.forEach { ids.edit().remove(it).apply() }
        _transfers.value = seen
        return seen.values.any { it is Transfer.Queued || it is Transfer.Running || it is Transfer.Waiting }
    }

    private fun Cursor.transfer(key: String): Transfer {
        val bytes = long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        val total = long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES).takeIf { it > 0 }
        val reason = int(DownloadManager.COLUMN_REASON)
        return when (int(DownloadManager.COLUMN_STATUS)) {
            DownloadManager.STATUS_PENDING -> Transfer.Queued(key)
            DownloadManager.STATUS_RUNNING -> Transfer.Running(key, bytes, total)
            DownloadManager.STATUS_PAUSED -> Transfer.Waiting(
                key, bytes, total,
                when (reason) {
                    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> TransferProblem.WaitingForWifi
                    DownloadManager.PAUSED_WAITING_FOR_NETWORK, DownloadManager.PAUSED_WAITING_TO_RETRY -> TransferProblem.WaitingForNetwork
                    else -> TransferProblem.Unknown
                },
            )
            DownloadManager.STATUS_SUCCESSFUL -> {
                val path = string(DownloadManager.COLUMN_LOCAL_URI)?.let { Uri.parse(it).path }.orEmpty()
                Transfer.Done(key, path, File(path).length().takeIf { it > 0 } ?: bytes)
            }
            else -> Transfer.Failed(
                key,
                when (reason) {
                    DownloadManager.ERROR_INSUFFICIENT_SPACE, DownloadManager.ERROR_DEVICE_NOT_FOUND -> TransferProblem.Storage
                    DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
                    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> TransferProblem.Server
                    in 400..599 -> TransferProblem.Server
                    else -> TransferProblem.Unknown
                },
                "DownloadManager reason $reason",
            )
        }
    }

    private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.string(column: String): String? = getString(getColumnIndexOrThrow(column))

    private companion object {
        const val POLL_MS = 1_000L
    }
}
