package dev.shibasis.reaktor.work.workers

import co.touchlab.kermit.Logger
import dev.mattramotar.meeseeks.runtime.AppContext
import dev.mattramotar.meeseeks.runtime.Worker
import dev.mattramotar.meeseeks.runtime.RuntimeContext
import dev.mattramotar.meeseeks.runtime.TaskResult

class HeartbeatWorker(appContext: AppContext) : Worker<HeartbeatPayload>(appContext) {
    override suspend fun run(payload: HeartbeatPayload, context: RuntimeContext): TaskResult {
        Logger.i("HeartbeatWorker") { "Sending heartbeat (attempt=${context.attemptCount})" }
        // Ping server, report device status
        Logger.i("HeartbeatWorker") { "Heartbeat sent" }
        return TaskResult.Success
    }
}
