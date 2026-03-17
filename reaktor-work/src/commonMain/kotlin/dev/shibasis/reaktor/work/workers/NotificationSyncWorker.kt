package dev.shibasis.reaktor.work.workers

import co.touchlab.kermit.Logger
import dev.mattramotar.meeseeks.runtime.AppContext
import dev.mattramotar.meeseeks.runtime.Worker
import dev.mattramotar.meeseeks.runtime.RuntimeContext
import dev.mattramotar.meeseeks.runtime.TaskResult

class NotificationSyncWorker(appContext: AppContext) : Worker<NotificationSyncPayload>(appContext) {
    override suspend fun run(payload: NotificationSyncPayload, context: RuntimeContext): TaskResult {
        Logger.i("NotificationSyncWorker") { "Syncing notification state (attempt=${context.attemptCount})" }
        // Sync read/dismissed state with server, fetch new notifications
        Logger.i("NotificationSyncWorker") { "Notification sync completed" }
        return TaskResult.Success
    }
}
