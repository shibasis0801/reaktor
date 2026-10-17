package dev.shibasis.reaktor.work

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970

/** One plist-declared identifier per configured host profile. Register before launch completes. */
class DarwinWorkScheduler(
    private val identifier: String,
    private val scope: WorkScope,
    private val host: WorkHost,
) : WorkScheduler {
    fun register(): Boolean = BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(identifier, null) { task ->
        if (task != null) {
            // Event-owned root; expiration cancels it and completion is called exactly once in finally.
            val eventJob = Job()
            val eventScope = CoroutineScope(Dispatchers.Default + eventJob)
            var success = false
            task.expirationHandler = { eventJob.cancel() }
            val attempt = eventScope.launch {
                    val session = host.open(scope)
                    try { session.runtime.drain(); success = true } finally { session.close() }
            }
            // Completion also runs when expiration cancels the job before its body starts.
            attempt.invokeOnCompletion { failure ->
                task.setTaskCompletedWithSuccess(success && failure == null)
                eventJob.complete()
            }
        }
    }

    override suspend fun arm(scope: WorkScope, nextWakeAtMillis: Long?) {
        require(scope == this.scope) { "Scope must match this registered profile" }
        if (nextWakeAtMillis == null) {
            BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(identifier)
            return
        }
        val request = BGProcessingTaskRequest(identifier)
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSince1970(nextWakeAtMillis / 1000.0)
        check(BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)) { "BGTaskScheduler rejected wake request" }
    }
}
