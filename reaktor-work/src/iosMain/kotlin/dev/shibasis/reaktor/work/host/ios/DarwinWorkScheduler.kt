package dev.shibasis.reaktor.work.host.ios

import dev.shibasis.reaktor.work.execution.WorkScheduler
import dev.shibasis.reaktor.work.host.LiveScheduler
import dev.shibasis.reaktor.work.host.WorkHost
import dev.shibasis.reaktor.work.host.drain
import dev.shibasis.reaktor.work.run.WorkScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import kotlin.concurrent.Volatile

class DarwinWorkScheduler(
    private val identifier: String,
    private val scope: WorkScope,
    private val host: WorkHost,
    private val lifetime: CoroutineScope,
) : WorkScheduler {
    private val foreground = LiveScheduler(lifetime, host)
    @Volatile private var active = false

    fun register(): Boolean {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(name = UIApplicationDidBecomeActiveNotification, `object` = null, queue = NSOperationQueue.mainQueue) { _: NSNotification? ->
            active = true
            lifetime.launch { host.drain(scope) }
        }
        center.addObserverForName(name = UIApplicationDidEnterBackgroundNotification, `object` = null, queue = NSOperationQueue.mainQueue) { _: NSNotification? ->
            active = false
            lifetime.launch { foreground.arm(scope, null) }
        }
        return BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(identifier, null) { task ->
            if (task != null) {
                val event = Job()
                var succeeded = false
                task.expirationHandler = { event.cancel() }
                CoroutineScope(Dispatchers.Default + event).launch {
                    host.drain(scope)
                    succeeded = true
                }.invokeOnCompletion { failure ->
                    task.setTaskCompletedWithSuccess(succeeded && failure == null)
                    event.complete()
                }
            }
        }
    }

    override suspend fun arm(scope: WorkScope, nextWakeAtMillis: Long?) {
        require(scope == this.scope) { "This scheduler is registered for another scope" }
        foreground.arm(scope, nextWakeAtMillis.takeIf { active })
        if (nextWakeAtMillis == null) {
            BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(identifier)
            return
        }
        val request = BGProcessingTaskRequest(identifier)
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSince1970(nextWakeAtMillis / 1000.0)
        check(BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)) { "BGTaskScheduler rejected the wake request" }
    }
}
