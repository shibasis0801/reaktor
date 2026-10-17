package dev.shibasis.reaktor.work

import dev.shibasis.reaktor.core.framework.json
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.runBlocking
import org.quartz.*
import java.util.Date
import java.security.MessageDigest

/** Supply Spring's configured Scheduler. This adapter never creates an in-memory substitute. */
class QuartzWorkScheduler(private val scheduler: Scheduler, host: WorkHost) : WorkScheduler {
    init { scheduler.context[HostKey] = host }

    override suspend fun arm(scope: WorkScope, nextWakeAtMillis: Long?) {
        val encoded = json.encodeToString(WorkScope.serializer(), scope)
        val identity = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") { "%02x".format(it) }
        val jobKey = JobKey(identity, "reaktor-work")
        val triggerKey = TriggerKey(identity, "reaktor-work")
        if (nextWakeAtMillis == null) { scheduler.unscheduleJob(triggerKey); return }
        val job = JobBuilder.newJob(ReaktorQuartzDrainJob::class.java).withIdentity(jobKey)
            .usingJobData(ScopeKey, encoded).storeDurably().requestRecovery().build()
        scheduler.addJob(job, true)
        val trigger = TriggerBuilder.newTrigger().withIdentity(triggerKey).forJob(jobKey)
            .startAt(Date(nextWakeAtMillis)).withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow()).build()
        try { scheduler.scheduleJob(trigger) }
        catch (exists: ObjectAlreadyExistsException) { scheduler.rescheduleJob(triggerKey, trigger) }
    }
}

@DisallowConcurrentExecution
class ReaktorQuartzDrainJob : InterruptableJob {
    @Volatile private var event: CoroutineJob? = null
    override fun execute(context: JobExecutionContext) {
        val host = context.scheduler.context[HostKey] as? WorkHost ?: throw JobExecutionException("Work host not bound at process startup")
        val scope = json.decodeFromString(WorkScope.serializer(), context.mergedJobDataMap.getString(ScopeKey))
        val lifetime = CoroutineJob()
        event = lifetime
        try {
            runBlocking(lifetime) {
                val session = host.open(scope)
                try { session.runtime.drain() } finally { session.close() }
            }
        } finally { event = null; lifetime.cancel() }
    }
    override fun interrupt() { event?.cancel() }
}

private const val HostKey = "reaktor.work.host"
private const val ScopeKey = "reaktor.work.scope"
