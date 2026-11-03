package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexCallsTest {
    private lateinit var home: File
    private val now = Instant.parse("2026-10-29T01:40:00Z").toEpochMilli()

    @BeforeTest fun machine() {
        home = Files.createTempDirectory("codex-calls-home").toFile().canonicalFile
    }

    @AfterTest fun clean() {
        home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
    }

    private fun rollout(id: String, vararg records: String, written: Long = now - 1_000): File {
        val day = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
        return home.resolve(".codex/sessions/%04d/%02d/%02d/rollout-2026-10-29T07-00-00-$id.jsonl".format(day.year, day.monthValue, day.dayOfMonth)).apply {
            parentFile.mkdirs()
            writeText(records.joinToString("\n", postfix = "\n"))
            setLastModified(written)
        }
    }

    private fun at(secondsAgo: Long) = Instant.ofEpochMilli(now - secondsAgo * 1000).toString()

    private fun turn(secondsAgo: Long = 60) = """{"timestamp":"${at(secondsAgo)}","type":"turn_context","payload":{}}"""

    private fun exec(id: String, script: String, secondsAgo: Long = 5) =
        """{"timestamp":"${at(secondsAgo)}","type":"response_item","payload":{"type":"custom_tool_call","call_id":"$id","name":"exec","input":${JsonPrimitive(script)}}}"""

    private fun function(id: String, name: String, arguments: String) =
        """{"timestamp":"${at(5)}","type":"response_item","payload":{"type":"function_call","call_id":"$id","name":"$name","arguments":${JsonPrimitive(arguments)}}}"""

    private fun output(id: String) = """{"timestamp":"${at(2)}","type":"response_item","payload":{"type":"custom_tool_call_output","call_id":"$id","output":"ok"}}"""

    @Test fun nativeMetadataNamesEachConcurrentThreadWithoutWaitingForItsRollout() {
        val stale = rollout("dead", turn(), exec("stale", """await tools.mcp__reaktor__kernel({operation:"plan.write"})"""))
        for (id in listOf("thread-a", "thread-b", "thread-c")) {
            assertEquals("codex:$id", CodexCalls.caller(buildJsonObject { put("threadId", id) }, null).thread)
        }
        assertNull(CodexCalls.caller(JsonObject(emptyMap()), null).thread)
        assertTrue(stale.isFile)
        assertNull(CodexCalls.caller(buildJsonObject { put("threadId", "../invalid") }, null).thread)
        assertNull(CodexCalls.caller(buildJsonObject { put("threadId", 17) }, null).thread)
    }

    @Test fun execAndResumeUseOnlyTheirProcessesOneOpenRollout() {
        val a = rollout("exec-a")
        rollout("dead", turn(), exec("stale", """await tools.mcp__reaktor__kernel({operation:"plan.write"})"""))
        assertEquals("codex:exec-a", CodexCalls.resolve(JsonObject(emptyMap()), listOf(a), true, now).thread)
        assertNull(CodexCalls.resolve(JsonObject(emptyMap()), emptyList(), true, now).thread)
        val resumed = rollout("resumed", written = now - 40 * 60_000)
        assertEquals("codex:resumed", CodexCalls.resolve(JsonObject(emptyMap()), listOf(resumed), true, now).thread)
    }

    @Test fun anAppServerResolvesEveryCallByMetadataOrExactCallIdOnlyAmongItsOwnRollouts() {
        val a = rollout("app-a", turn(), function("call-a", "mcp__reaktor__kernel", """{"operation":"plan.write"}"""))
        val b = rollout("app-b", turn(), function("call-b", "mcp__reaktor__kernel", """{"operation":"plan.write"}"""))
        val files = listOf(a, b)
        for ((thread, id) in listOf("app-a" to "call-a", "app-b" to "call-b")) {
            assertEquals("codex:$thread", CodexCalls.resolve(buildJsonObject { put("callId", id) }, files, false, now).thread)
            assertEquals("codex:$thread", CodexCalls.resolve(buildJsonObject { put("threadId", thread) }, files, false, now).thread)
        }
        assertNull(CodexCalls.resolve(JsonObject(emptyMap()), listOf(a), false, now).thread)
        assertNull(CodexCalls.resolve(buildJsonObject { put("callId", "not-flushed") }, files, false, now).thread)
        b.appendText(output("call-b") + "\n")
        assertNull(CodexCalls.resolve(buildJsonObject { put("callId", "call-b") }, files, false, now).thread)
        val duplicate = rollout("copy", turn(), function("call-a", "mcp__reaktor__kernel", "{}"))
        assertNull(CodexCalls.resolve(buildJsonObject { put("callId", "call-a") }, files + duplicate, false, now).thread)
    }

    @Test fun anExitedProcessCannotContributeItsRollouts() {
        rollout("dead", turn(), exec("stale", """await tools.mcp__reaktor__kernel({operation:"plan.write"})"""))
        val process = ProcessBuilder("sh", "-c", "exit 0").start()
        val handle = process.toHandle()
        process.waitFor()
        val caller = CodexCalls.caller(buildJsonObject { put("callId", "stale") }, handle)
        assertNull(caller.thread)
        assertTrue(caller.diagnostic?.contains("No live") == true)
    }

    @Test fun aNoteCountsAsShownOnlyOnceAToolOutputAfterItsDeliveryCarriesIt() {
        val note = "Hangar, from the owner (Shibasis), 29 Oct:"
        val earlier = """{"timestamp":"${at(30)}","type":"response_item","payload":{"type":"custom_tool_call_output","call_id":"c0","output":[{"type":"input_text","text":"$note seen before"}]}}"""
        val file = rollout("01a3-aaaa", turn(), earlier)
        val from = file.length()
        file.appendText(exec("c1", """const r = await tools.mcp__reaktor__kernel({operation:"run.wait"}); text(JSON.parse(r.content[0].text).type)""") + "\n")
        file.appendText("""{"timestamp":"${at(4)}","type":"event_msg","payload":{"type":"item_completed","item":{"type":"McpToolCall","tool":"kernel","result":"$note in the result only"}}}""" + "\n")
        file.appendText("""{"timestamp":"${at(3)}","type":"response_item","payload":{"type":"custom_tool_call_output","call_id":"c1","output":[{"type":"input_text","text":"completed"}]}}""" + "\n")
        assertFalse(CodexCalls.shown(file, from, note))
        file.appendText("""{"timestamp":"${at(1)}","type":"response_item","payload":{"type":"function_call_output","call_id":"f2","output":"{\"inbox\":[\"$note \\\"instruct\\\"\"]}"}}""" + "\n")
        assertTrue(CodexCalls.shown(file, from, note))
    }
}
