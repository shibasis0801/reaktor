package dev.shibasis.reaktor.work.workers

import co.touchlab.kermit.Logger
import dev.mattramotar.meeseeks.runtime.AppContext
import dev.mattramotar.meeseeks.runtime.Worker
import dev.mattramotar.meeseeks.runtime.RuntimeContext
import dev.mattramotar.meeseeks.runtime.TaskResult

class MediaUploadWorker(appContext: AppContext) : Worker<MediaUploadPayload>(appContext) {
    override suspend fun run(payload: MediaUploadPayload, context: RuntimeContext): TaskResult {
        Logger.i("MediaUploadWorker") { "Uploading media ${payload.mediaId} (attempt=${context.attemptCount})" }
        // Upload media file to storage service
        Logger.i("MediaUploadWorker") { "Media upload completed for ${payload.mediaId}" }
        return TaskResult.Success
    }
}
