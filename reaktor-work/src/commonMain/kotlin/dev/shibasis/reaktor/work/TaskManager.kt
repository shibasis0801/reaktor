package dev.shibasis.reaktor.work

import dev.mattramotar.meeseeks.runtime.AppContext
import dev.mattramotar.meeseeks.runtime.BGTaskManager
import dev.mattramotar.meeseeks.runtime.ConfigurationScope
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
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

abstract class TaskManager<Controller>(
    controller: Controller,
    private val configure: ConfigurationScope.() -> Unit = {}
) : Adapter<Controller>(controller) {

    protected abstract fun createAppContext(): AppContext

    @PublishedApi
    internal val manager: BGTaskManager by lazy {
        Meeseeks.initialize(createAppContext()) {
            minBackoff(15.seconds)
            maxRetryCount(3)
            maxParallelTasks(4)
            configure()
        }
    }

    // Call during app startup to eagerly initialize (required on iOS for BGTaskScheduler registration)
    fun initialize() { manager }

    inline fun <reified T : TaskPayload> oneTime(
        payload: T,
        initialDelay: Duration = Duration.ZERO,
        noinline configure: OneTimeTaskRequestConfigurationScope<T>.() -> Unit = {}
    ): TaskHandle = manager.oneTime(payload, initialDelay, configure)

    inline fun <reified T : TaskPayload> periodic(
        payload: T,
        every: Duration,
        initialDelay: Duration = Duration.ZERO,
        flexWindow: Duration = Duration.ZERO,
        noinline configure: PeriodicTaskRequestConfigurationScope<T>.() -> Unit = {}
    ): TaskHandle = manager.periodic(payload, every, initialDelay, flexWindow, configure)

    fun cancel(id: TaskId) = manager.cancel(id)
    fun cancelAll() = manager.cancelAll()
    fun getTaskStatus(id: TaskId): TaskStatus? = manager.getTaskStatus(id)
    fun listTasks(): List<ScheduledTask> = manager.listTasks()
    fun observeStatus(id: TaskId): Flow<TaskStatus?> = manager.observeStatus(id)
    suspend fun reschedule(id: TaskId, updatedRequest: TaskRequest): TaskId = manager.reschedule(id, updatedRequest)
}

var Feature.Work by CreateSlot<TaskManager<*>>()
