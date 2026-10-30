package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.json.JsonPrimitive
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

    @Test fun aKernelCallIsTracedToTheOneThreadWhoseRunningScriptMakesIt() {
        rollout("01a1-aaaa", turn(), exec("c1", """const r = await tools.mcp__reaktor__kernel({operation:"board.list"}); text(r)"""))
        rollout("01a1-bbbb", turn(), exec("c2", """await tools.mcp__reaktor__kernel({operation:"board.list"})"""), output("c2"))
        rollout("01a1-cccc", turn(), exec("c3", """await tools.mcp__reaktor_manna__kernel({operation:'ship.plan', arguments:{}})"""))
        rollout("01a1-dddd", turn(), function("f1", "mcp__reaktor__kernel", """{"operation":"plan.read","arguments":{}}"""))

        assertEquals("codex:01a1-aaaa", CodexCalls.kernelCall("board.list", home, now)?.thread)
        assertEquals("codex:01a1-cccc", CodexCalls.kernelCall("ship.plan", home, now)?.thread)
        assertEquals("codex:01a1-dddd", CodexCalls.kernelCall("plan.read", home, now)?.thread)
        assertNull(CodexCalls.kernelCall("board", home, now))
        assertNull(CodexCalls.kernelCall("agent.radar", home, now))
    }

    @Test fun twoThreadsMakingTheSameCallOrAFinishedTurnNameNobody() {
        rollout("01a2-aaaa", turn(), exec("c1", """await tools.mcp__reaktor__kernel({operation:"board.list"})"""))
        rollout("01a2-bbbb", turn(), exec("c2", """await tools.mcp__reaktor__kernel({operation:"board.list"})"""))
        rollout("01a2-cccc", turn(120), exec("c3", """await tools.mcp__reaktor__kernel({operation:"plan.write"})""", secondsAgo = 100), turn(30))
        rollout("01a2-dddd", turn(3_000), exec("c4", """await tools.mcp__reaktor__kernel({operation:"kernel.status"})""", secondsAgo = 2_400))
        rollout("01a2-eeee", turn(), exec("c5", """text("plan.read is the call to make")"""))
        rollout("01a2-ffff", turn(), exec("c6", """await tools.mcp__reaktor__kernel({operation:"agent.radar"})"""), written = now - 40 * 60_000)

        assertNull(CodexCalls.kernelCall("board.list", home, now))
        assertNull(CodexCalls.kernelCall("plan.write", home, now))
        assertNull(CodexCalls.kernelCall("kernel.status", home, now))
        assertNull(CodexCalls.kernelCall("plan.read", home, now))
        assertNull(CodexCalls.kernelCall("agent.radar", home, now))
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
