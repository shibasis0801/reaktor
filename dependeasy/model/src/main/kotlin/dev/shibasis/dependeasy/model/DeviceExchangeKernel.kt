package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.model.device.CommandRunner
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

object DeviceExchangeKernel {
    fun run(spec: KernelSpec.DeviceExchange, executions: List<DeviceFlowExecution>,
        execute: (List<String>, Map<String, String>) -> CommandRunner.Result, log: (String) -> Unit) {
        require(spec.phones.size >= 2 && spec.phones.size == executions.size) { "Declare at least two phones for a device exchange" }
        val sessions = mutableListOf<DeviceSession>()
        var success = false
        try {
            spec.phones.zip(executions).forEach { (phone, execution) -> sessions.add(DeviceSession.open(phone, execution, execute)) }
            sessions.forEach(DeviceSession::install)
            val failures = sessions.flatMap { receiver ->
                sessions.filter { it !== receiver }.mapNotNull { sender -> exchange(spec, sender, receiver, log) }
            }
            check(failures.isEmpty()) { failures.joinToString("\n") }
            success = true
        } finally {
            var failure: Throwable? = null
            for (session in sessions.asReversed()) try { session.close(success) } catch (error: Throwable) {
                failure = failure?.apply { addSuppressed(error) } ?: error
            }
            failure?.let { throw it }
        }
    }

    private fun exchange(spec: KernelSpec.DeviceExchange, sender: DeviceSession, receiver: DeviceSession, log: (String) -> Unit): String? {
        val direction = "${sender.platform}-to-${receiver.platform}"
        val token = mapOf(spec.token to "${sender.platform} to ${receiver.platform} ${UUID.randomUUID().toString().substringBefore('-')}")
        var sent = false
        var received = false
        receiver.begin(listOf(receiver.flow(spec.receive)), receiver.report(direction))
        return try {
            sender.begin(listOf(sender.flow(spec.send)), sender.report(direction))
            try {
                val (early, sending, receipt) = runBlocking(Dispatchers.IO) {
                    val receiving = async { receiver.test(receiver.environment + token) }
                    val early = withTimeoutOrNull(spec.sendAfterSeconds.seconds) { receiving.await() }
                    val sending = if (early == null) sender.test(sender.environment + token) else null
                    Triple(early, sending, early ?: receiving.await())
                }
                sending?.let { log(it.output) }
                log(receipt.output)
                sent = sending?.succeeded == true
                received = early == null && receipt.succeeded
                if (sent && received) null else "Device exchange $direction failed: " + when {
                    early != null -> "the ${receiver.platform} phone finished ${spec.receive} before ${spec.send} started"
                    !sent -> "the ${sender.platform} phone failed ${spec.send}"
                    else -> "the ${receiver.platform} phone failed ${spec.receive}"
                }
            } finally { sender.end(sent) }
        } finally { receiver.end(received) }
    }
}
