package dev.shibasis.reaktor.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.shibasis.reaktor.core.framework.json
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Implemented by Application so OS reconstruction never depends on Activity registration. */
interface AndroidWorkApplication {
    val reaktorWorkHost: WorkHost
}

class AndroidWorkScheduler(context: Context) : WorkScheduler {
    private val manager = WorkManager.getInstance(context.applicationContext)
    override suspend fun arm(scope: WorkScope, nextWakeAtMillis: Long?) {
        if (nextWakeAtMillis == null) return
        val encoded = json.encodeToString(WorkScope.serializer(), scope)
        val request = OneTimeWorkRequestBuilder<ReaktorDrainWorker>()
            .setInputData(workDataOf(ScopeKey to encoded))
            .setInitialDelay(maxOf(0, nextWakeAtMillis - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .addTag("reaktor-work")
            .build()
        // A new wake must not cancel the executing attempt. Duplicate wakes are harmless to fenced claims.
        val operation = manager.enqueueUniqueWork("reaktor-work:$encoded:$nextWakeAtMillis", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        suspendCancellableCoroutine<Unit> { continuation ->
            operation.result.addListener({
                try { operation.result.get(); continuation.resume(Unit) }
                catch (failure: Exception) { continuation.resumeWithException(failure) }
            }, Executor { it.run() })
        }
    }
}

class ReaktorDrainWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val encoded = inputData.getString(ScopeKey) ?: return Result.failure()
        val scope = try { json.decodeFromString(WorkScope.serializer(), encoded) } catch (failure: Exception) { return Result.failure() }
        val host = (applicationContext as? AndroidWorkApplication)?.reaktorWorkHost ?: return Result.failure()
        val session = host.open(scope)
        return try { session.runtime.drain(); Result.success() } finally { session.close() }
    }
}

private const val ScopeKey = "reaktor.work.scope"
