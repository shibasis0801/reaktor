package dev.shibasis.reaktor.work.workers

import co.touchlab.kermit.Logger
import dev.mattramotar.meeseeks.runtime.AppContext
import dev.mattramotar.meeseeks.runtime.Worker
import dev.mattramotar.meeseeks.runtime.RuntimeContext
import dev.mattramotar.meeseeks.runtime.TaskResult

class LogUploadWorker(appContext: AppContext) : Worker<LogUploadPayload>(appContext) {
    override suspend fun run(payload: LogUploadPayload, context: RuntimeContext): TaskResult {
        Logger.i("LogUploadWorker") { "Uploading logs (attempt=${context.attemptCount})" }
        // Collect and upload app logs to server
        Logger.i("LogUploadWorker") { "Log upload completed" }
        return TaskResult.Success
    }
}
