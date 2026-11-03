package dev.shibasis.reaktor.io.network

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorBadServerResponse
import platform.Foundation.NSURLErrorBadURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDataNotAllowed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorFileDoesNotExist
import platform.Foundation.NSURLErrorHTTPTooManyRedirects
import platform.Foundation.NSURLErrorInternationalRoamingOff
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorRedirectToNonExistentLocation
import platform.Foundation.NSURLErrorResourceUnavailable
import platform.Foundation.NSURLErrorSecureConnectionFailed
import platform.Foundation.NSURLErrorServerCertificateUntrusted
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLErrorUnsupportedURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionDownloadTaskResumeData
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskStateCompleted
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.Foundation.NSHTTPURLResponse
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * [BackgroundDownloads] on a background `URLSession`.
 *
 * `nsurlsessiond` does the transfer, not the app: it carries on with the app suspended or killed,
 * waits for Wi-Fi when cellular is not allowed, and relaunches the app in the background to report
 * what finished. For that last part the app delegate must hand the event over:
 *
 * ```swift
 * func application(_ application: UIApplication,
 *                  handleEventsForBackgroundURLSession identifier: String,
 *                  completionHandler: @escaping () -> Void) {
 *     DarwinBackgroundDownloads.companion.handleEvents(identifier: identifier, completion: completionHandler)
 * }
 * ```
 *
 * Each task carries its key and file name in its `taskDescription`, which the system keeps with the
 * task across relaunches — so nothing else has to be stored to know whose a finished file is. A
 * transfer that fails partway keeps the system's resume data on disk and picks up from there the
 * next time the same key is enqueued.
 *
 * Build one per process, early: creating the session is what makes the system deliver the events
 * it held while the app was not running.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class DarwinBackgroundDownloads(
    identifier: String = (NSBundle.mainBundle.bundleIdentifier ?: "reaktor") + ".downloads",
) : BackgroundDownloads {

    override val directory: String = applicationSupport("Downloads")

    private val _transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    override val transfers: StateFlow<Map<String, Transfer>> = _transfers.asStateFlow()

    private val delegate = Delegate()
    private val session: NSURLSession = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(identifier).apply {
            sessionSendsLaunchEvents = true
            discretionary = false
        },
        delegate = delegate,
        delegateQueue = null,
    )

    override fun enqueue(request: DownloadRequest) {
        require('/' !in request.fileName) { "fileName must be a plain file name: ${request.fileName}" }
        cancelTasks(request.key) {
            val resume = NSData.dataWithContentsOfFile(resumePath(request.key))
            val task = if (resume != null) {
                session.downloadTaskWithResumeData(resume)
            } else {
                val url = NSURL.URLWithString(request.url)
                if (url == null) {
                    publish(Transfer.Failed(request.key, TransferProblem.Server, "Not an address: ${request.url}"))
                    return@cancelTasks
                }
                val urlRequest = NSMutableURLRequest(uRL = url).apply {
                    setAllowsCellularAccess(request.allowMetered)
                    setAllowsExpensiveNetworkAccess(request.allowMetered)
                }
                session.downloadTaskWithRequest(urlRequest)
            }
            task.taskDescription = json.encodeToString(Tag.serializer(), Tag(request.key, request.fileName))
            NSFileManager.defaultManager.removeItemAtPath(resumePath(request.key), null)
            task.resume()
            publish(Transfer.Queued(request.key))
        }
    }

    override fun remove(key: String) {
        cancelTasks(key) {}
        val manager = NSFileManager.defaultManager
        manager.removeItemAtPath(resumePath(key), null)
        (transfers.value[key] as? Transfer.Done)?.let { manager.removeItemAtPath(it.path, null) }
        _transfers.update { it - key }
    }

    override suspend fun reconcile() {
        val tasks = suspendCancellableCoroutine { continuation ->
            session.getAllTasksWithCompletionHandler { list -> continuation.resume(list.orEmpty()) }
        }
        tasks.filterIsInstance<NSURLSessionTask>().forEach { task ->
            val tag = tag(task) ?: return@forEach
            if (task.state != NSURLSessionTaskStateCompleted) {
                publish(Transfer.Running(tag.key, task.countOfBytesReceived, task.countOfBytesExpectedToReceive.takeIf { it > 0 }))
            }
        }
    }

    /** Cancels any task for [key], then runs [then] once the system has listed them. */
    private fun cancelTasks(key: String, then: () -> Unit) {
        session.getAllTasksWithCompletionHandler { list ->
            list.orEmpty().filterIsInstance<NSURLSessionTask>()
                .filter { tag(it)?.key == key }
                .forEach { it.cancel() }
            then()
        }
    }

    private fun publish(transfer: Transfer) = _transfers.update { it + (transfer.key to transfer) }

    private fun resumePath(key: String) = "$directory/.${key.hashCode().toUInt().toString(16)}.resume"

    private inner class Delegate : NSObject(), NSURLSessionDownloadDelegateProtocol {
        override fun URLSession(session: NSURLSession, downloadTask: NSURLSessionDownloadTask, didFinishDownloadingToURL: NSURL) {
            val tag = tag(downloadTask) ?: return
            val status = (downloadTask.response as? NSHTTPURLResponse)?.statusCode ?: 200
            if (status !in 200..299) {
                publish(Transfer.Failed(tag.key, TransferProblem.Server, "HTTP $status"))
                return
            }
            // The system deletes the temporary file as soon as this returns, so it moves now.
            val target = "$directory/${tag.fileName}"
            val manager = NSFileManager.defaultManager
            manager.removeItemAtPath(target, null)
            val moved = manager.moveItemAtURL(didFinishDownloadingToURL, NSURL.fileURLWithPath(target), null)
            if (!moved) {
                publish(Transfer.Failed(tag.key, TransferProblem.Storage, "Could not move the file into place"))
                return
            }
            val size = (manager.attributesOfItemAtPath(target, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
            publish(Transfer.Done(tag.key, target, size))
        }

        override fun URLSession(
            session: NSURLSession,
            downloadTask: NSURLSessionDownloadTask,
            didWriteData: Long,
            totalBytesWritten: Long,
            totalBytesExpectedToWrite: Long,
        ) {
            val tag = tag(downloadTask) ?: return
            publish(Transfer.Running(tag.key, totalBytesWritten, totalBytesExpectedToWrite.takeIf { it > 0 }))
        }

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            val error = didCompleteWithError ?: return
            val tag = tag(task) ?: return
            if (error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled) return
            // Keep what was fetched, so asking again carries on rather than starting over.
            (error.userInfo[NSURLSessionDownloadTaskResumeData] as? NSData)?.writeToFile(resumePath(tag.key), atomically = true)
            // Only what is plainly the server's doing is blamed on it. The rest — the background
            // daemon refusing the app, NSURLErrorUnknown — is Unknown, which asks for a retry.
            val problem = when {
                error.domain != NSURLErrorDomain -> TransferProblem.Unknown
                error.code in offline -> TransferProblem.WaitingForNetwork
                error.code in refused -> TransferProblem.Server
                else -> TransferProblem.Unknown
            }
            publish(Transfer.Failed(tag.key, problem, "${error.domain} ${error.code}"))
        }

        override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
            dispatch_async(dispatch_get_main_queue()) {
                completions.remove(session.configuration.identifier)?.invoke()
            }
        }
    }

    @Serializable
    private data class Tag(val key: String, val fileName: String)

    private fun tag(task: NSURLSessionTask): Tag? =
        task.taskDescription?.let { runCatching { json.decodeFromString(Tag.serializer(), it) }.getOrNull() }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val completions = mutableMapOf<String, () -> Unit>()

        private val offline = setOf(
            NSURLErrorNotConnectedToInternet, NSURLErrorNetworkConnectionLost, NSURLErrorTimedOut,
            NSURLErrorDataNotAllowed, NSURLErrorInternationalRoamingOff,
        )
        private val refused = setOf(
            NSURLErrorBadURL, NSURLErrorUnsupportedURL, NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost,
            NSURLErrorBadServerResponse, NSURLErrorResourceUnavailable, NSURLErrorFileDoesNotExist,
            NSURLErrorHTTPTooManyRedirects, NSURLErrorRedirectToNonExistentLocation,
            NSURLErrorSecureConnectionFailed, NSURLErrorServerCertificateUntrusted,
        )

        /**
         * From the app delegate's `handleEventsForBackgroundURLSession`. The system woke the app to
         * report finished downloads; [completion] tells it the app is done handling them, after
         * the session has delivered every event.
         */
        fun handleEvents(identifier: String, completion: () -> Unit) {
            completions[identifier] = completion
        }

        private fun applicationSupport(folder: String): String {
            val manager = NSFileManager.defaultManager
            val base = (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).firstOrNull() as NSURL)
            val url = base.URLByAppendingPathComponent(folder)!!
            manager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
            // Episodes can be fetched again; they do not belong in someone's iCloud backup.
            url.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
            return url.path!!
        }
    }
}
