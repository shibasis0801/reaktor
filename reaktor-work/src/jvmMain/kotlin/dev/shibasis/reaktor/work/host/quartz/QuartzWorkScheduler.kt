package dev.shibasis.reaktor.work.host.quartz

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.work.execution.WorkScheduler
import dev.shibasis.reaktor.work.host.WorkHost
import dev.shibasis.reaktor.work.host.drain
import dev.shibasis.reaktor.work.run.WorkScope
import kotlinx.coroutines.runBlocking
import org.quartz.DisallowConcurrentExecution
import org.quartz.InterruptableJob
import org.quartz.JobBuilder
import org.quartz.JobExecutionContext
import org.quartz.JobExecutionException
import org.quartz.JobKey
import org.quartz.ObjectAlreadyExistsException
import org.quartz.Scheduler
import org.quartz.SimpleScheduleBuilder
import org.quartz.TriggerBuilder
import org.quartz.TriggerKey
import java.security.MessageDigest
import java.util.Date
import kotlinx.coroutines.Job as CoroutineJob

class QuartzWorkScheduler(private val scheduler: Scheduler, host: WorkHost) : WorkScheduler {
    init { scheduler.context[HostKey] = host }

    override suspend fun arm(scope: WorkScope, nextWakeAtMillis: Long?) {
        val encoded = json.encodeToString(WorkScope.serializer(), scope)
        val identity = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") { "%02x".format(it) }
        val jobKey = JobKey(identity, "reaktor-work")
        val triggerKey = TriggerKey(identity, "reaktor-work")
        if (nextWakeAtMillis == null) {
            scheduler.unscheduleJob(triggerKey)
            return
        }
        val job = JobBuilder.newJob(ReaktorQuartzDrainJob::class.java).withIdentity(jobKey)
            .usingJobData(ScopeKey, encoded).storeDurably().requestRecovery().build()
        scheduler.addJob(job, true)
        val trigger = TriggerBuilder.newTrigger().withIdentity(triggerKey).forJob(jobKey).startAt(Date(nextWakeAtMillis))
            .withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow()).build()
        try {
            scheduler.scheduleJob(trigger)
        } catch (exists: ObjectAlreadyExistsException) {
            scheduler.rescheduleJob(triggerKey, trigger)
        }
    }
}

@DisallowConcurrentExecution
class ReaktorQuartzDrainJob : InterruptableJob {
    @Volatile private var event: CoroutineJob? = null

    override fun execute(context: JobExecutionContext) {
        val host = context.scheduler.context[HostKey] as? WorkHost ?: throw JobExecutionException("No Work host is bound to this scheduler")
        val scope = json.decodeFromString(WorkScope.serializer(), context.mergedJobDataMap.getString(ScopeKey))
        val lifetime = CoroutineJob()
        event = lifetime
        try {
            runBlocking(lifetime) { host.drain(scope) }
        } finally {
            event = null
            lifetime.cancel()
        }
    }

    override fun interrupt() {
        event?.cancel()
    }
}

private const val HostKey = "reaktor.work.host"
private const val ScopeKey = "reaktor.work.scope"
