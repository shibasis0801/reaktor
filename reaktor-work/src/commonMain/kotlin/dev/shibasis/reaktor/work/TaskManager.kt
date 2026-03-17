package dev.shibasis.reaktor.work

import dev.mattramotar.meeseeks.runtime.AppContext
import dev.mattramotar.meeseeks.runtime.BGTaskManager
import dev.mattramotar.meeseeks.runtime.Meeseeks
import dev.mattramotar.meeseeks.runtime.ScheduledTask
import dev.mattramotar.meeseeks.runtime.TaskHandle
import dev.mattramotar.meeseeks.runtime.TaskId
import dev.mattramotar.meeseeks.runtime.TaskPayload
import dev.mattramotar.meeseeks.runtime.TaskRequest
import dev.mattramotar.meeseeks.runtime.TaskStatus
import dev.mattramotar.meeseeks.runtime.dsl.OneTimeTaskRequestConfigurationScope
import dev.mattramotar.meeseeks.runtime.dsl.PeriodicTaskRequestConfigurationScope
import dev.mattramotar.meeseeks.runtime.oneTime
import dev.mattramotar.meeseeks.runtime.periodic
import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.work.workers.*
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

abstract class TaskManager<Controller>(
    controller: Controller
) : Adapter<Controller>(controller) {

    protected abstract fun createAppContext(): AppContext

    @PublishedApi
    internal var _manager: BGTaskManager? = null

    @PublishedApi
    internal fun getManager(): BGTaskManager {
        if (_manager == null) {
            _manager = Meeseeks.initialize(createAppContext()) {
                minBackoff(15.seconds)
                maxRetryCount(3)
                maxParallelTasks(4)

                register<SyncPayload> { SyncWorker(it) }
                register<CacheCleanupPayload> { CacheCleanupWorker(it) }
                register<AnalyticsUploadPayload> { AnalyticsUploadWorker(it) }
                register<MediaUploadPayload> { MediaUploadWorker(it) }
                register<DatabaseMaintenancePayload> { DatabaseMaintenanceWorker(it) }
                register<PrefetchPayload> { PrefetchWorker(it) }
                register<HeartbeatPayload> { HeartbeatWorker(it) }
                register<TokenRefreshPayload> { TokenRefreshWorker(it) }
                register<LogUploadPayload> { LogUploadWorker(it) }
                register<NotificationSyncPayload> { NotificationSyncWorker(it) }
            }
        }
        return _manager!!
    }

    inline fun <reified T : TaskPayload> oneTime(
        payload: T,
        initialDelay: Duration = Duration.ZERO,
        noinline configure: OneTimeTaskRequestConfigurationScope<T>.() -> Unit = {}
    ): TaskHandle = getManager().oneTime(payload, initialDelay, configure)

    inline fun <reified T : TaskPayload> periodic(
        payload: T,
        every: Duration,
        initialDelay: Duration = Duration.ZERO,
        flexWindow: Duration = Duration.ZERO,
        noinline configure: PeriodicTaskRequestConfigurationScope<T>.() -> Unit = {}
    ): TaskHandle = getManager().periodic(payload, every, initialDelay, flexWindow, configure)

    fun cancel(id: TaskId) = getManager().cancel(id)
    fun cancelAll() = getManager().cancelAll()
    fun getTaskStatus(id: TaskId): TaskStatus? = getManager().getTaskStatus(id)
    fun listTasks(): List<ScheduledTask> = getManager().listTasks()
    fun observeStatus(id: TaskId): Flow<TaskStatus?> = getManager().observeStatus(id)
    suspend fun reschedule(id: TaskId, updatedRequest: TaskRequest): TaskId = getManager().reschedule(id, updatedRequest)
}

var Feature.Work by CreateSlot<TaskManager<*>>()
