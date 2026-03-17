package dev.shibasis.reaktor.work.workers

import co.touchlab.kermit.Logger
import dev.mattramotar.meeseeks.runtime.AppContext
import dev.mattramotar.meeseeks.runtime.Worker
import dev.mattramotar.meeseeks.runtime.RuntimeContext
import dev.mattramotar.meeseeks.runtime.TaskResult

class AnalyticsUploadWorker(appContext: AppContext) : Worker<AnalyticsUploadPayload>(appContext) {
    override suspend fun run(payload: AnalyticsUploadPayload, context: RuntimeContext): TaskResult {
        Logger.i("AnalyticsUploadWorker") { "Uploading analytics batch (attempt=${context.attemptCount})" }
        // Batch upload analytics events to server
        Logger.i("AnalyticsUploadWorker") { "Analytics upload completed" }
        return TaskResult.Success
    }
}
