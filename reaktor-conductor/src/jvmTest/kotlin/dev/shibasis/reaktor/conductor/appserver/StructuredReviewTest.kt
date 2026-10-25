package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.ClaudeCodeRuntime
import dev.shibasis.reaktor.conductor.workspace.TaskReview
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class StructuredReviewTest {
    private val candidate = "a".repeat(64)
    private fun resource(name: String) = requireNotNull(javaClass.getResource("/cli/$name")).readText()

    private fun fixture(body: suspend (File, CoroutineScope) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("structured-review").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { body(root, scope) }
        finally { scope.cancel(); root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun codexUsageLimitProtocolKeepsThrottleRetryAndChildSignalsSeparate() = fixture { root, scope ->
        val binary = File(root, "codex-limit-fixture").apply {
            writeText("""#!/usr/bin/env python3
import sys,json
def send(value): print(json.dumps(value),flush=True)
for line in sys.stdin:
 r=json.loads(line); method=r.get('method'); ident=r.get('id')
 if method=='initialize': send({'id':ident,'result':{}})
 elif method=='thread/start': send({'id':ident,'result':{'thread':{'id':'parent'}}})
 elif method=='turn/start':
  send({'id':ident,'result':{'turn':{'id':'turn'}}})
  for event in json.load(open('events.json')): send(event)
 elif method=='account/rateLimits/read': send({'id':ident,'result':json.load(open('quota.json'))})
""")
            setExecutable(true)
        }
        val request = AgentRequest(AgentSpec(AgentId("writer"), "Writer", RuntimeKind.Codex, ""), "Work", root.path)
        fun event(method: String, params: String) = buildJsonObject { put("method", method); put("params", Json.parseToJsonElement(params)) }
        fun failed(code: String, origin: String = "parent") = event("turn/completed",
            """{"threadId":"$origin","turn":{"id":"turn","status":"failed","error":{"message":"Stopped","codexErrorInfo":$code}}}""")
        val success = event("turn/completed", """{"threadId":"parent","turn":{"id":"turn","status":"completed","items":[{"type":"agentMessage","text":"Done"}]}}""")
        suspend fun probe(events: List<JsonObject>, quota: String = "{}"): AgentOutcome {
            File(root, "events.json").writeText(JsonArray(events).toString())
            File(root, "quota.json").writeText(quota)
            return withTimeout(10_000) { CodexAppServerRuntime(binary.path, scope).run(request).toList() }
                .filterIsInstance<AgentEvent.Finished>().single().outcome
        }
        val limited = probe(listOf(failed("\"usageLimitExceeded\"")),
            """{"rateLimits":{"primary":{"usedPercent":100,"resetsAt":1999999999}},"rateLimitsByLimitId":{"codex":{"primary":{"usedPercent":100,"resetsAt":2000000000},"secondary":{"usedPercent":100,"resetsAt":2000000900}}}}""")
        assertFalse(limited.ok)
        assertEquals(AgentUsageLimit(RuntimeKind.Codex, "usageLimitExceeded", 2000000900000L), limited.usageLimit)
        val updated = event("account/rateLimits/updated", """{"rateLimits":{"primary":{"usedPercent":100,"resetsAt":2000000000}}}""")
        val sparse = event("account/rateLimits/updated", """{"rateLimits":{"secondary":null}}""")
        assertEquals(2000000000000L, probe(listOf(updated, sparse, failed("\"usageLimitExceeded\""))).usageLimit?.resetsAtEpochMillis)
        assertNull(probe(listOf(failed("\"usageLimitExceeded\""))).usageLimit?.resetsAtEpochMillis)
        listOf("\"rateLimitExceeded\"", "\"sessionBudgetExceeded\"", """{"other":"capacity"}""").forEach { code ->
            assertNull(probe(listOf(failed(code))).usageLimit)
        }
        val retrying = event("error", """{"threadId":"parent","willRetry":true,"error":{"message":"Retrying","codexErrorInfo":"usageLimitExceeded"}}""")
        val resumed = probe(listOf(retrying, failed("\"usageLimitExceeded\"", "child"), success))
        assertTrue(resumed.ok)
        assertNull(resumed.usageLimit)
        val terminal = event("error", """{"threadId":"parent","willRetry":false,"error":{"message":"Plan limit","codexErrorInfo":"usageLimitExceeded"}}""")
        assertNotNull(probe(listOf(terminal)).usageLimit)
    }

    @Test fun claudeUsageLimitProtocolKeepsWarningsOverageAndChildSignalsSeparate() {
        fun parse(vararg events: String): AgentOutcome {
            val parser = dev.shibasis.reaktor.conductor.cli.ClaudeCodeEventParser(AgentId("writer"))
            events.forEach(parser::onLine)
            return parser.finish(0, "")
        }
        val rejected = """{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","resetsAt":2000000000,"rateLimitType":"five_hour"}}"""
        val warning = """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed_warning","resetsAt":2000000000}}"""
        val allowed = """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"}}"""
        val failed = """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"Stopped"}"""
        val success = """{"type":"result","subtype":"success","is_error":false,"result":"Done"}"""
        assertEquals(AgentUsageLimit(RuntimeKind.ClaudeCode, "usage_limit_reached", 2000000000000L), parse(rejected, failed).usageLimit)
        assertNull(parse(warning, failed).usageLimit)
        assertNull(parse(rejected, success).usageLimit)
        assertNull(parse(rejected, allowed, failed).usageLimit)
        assertNull(parse("""{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","isUsingOverage":true}}""", failed).usageLimit)
        val typed = """{"type":"assistant","api_error":"usage_limit_reached","message":{"content":[]}}"""
        assertEquals(AgentUsageLimit(RuntimeKind.ClaudeCode, "usage_limit_reached"), parse(typed, failed).usageLimit)
        assertNull(parse(typed.replace("usage_limit_reached", "rate_limit"), failed).usageLimit)
        assertNull(parse(typed.dropLast(1) + ""","parent_tool_use_id":"child"}""", failed).usageLimit)
        assertNull(parse(rejected.dropLast(1) + ""","parent_tool_use_id":"child"}""", failed).usageLimit)
    }

    @Test fun claudeBothTransportsRetainTheReportedUsageLimit() = fixture { root, scope ->
        val binary = File(root, "claude-limit-fixture").apply {
            writeText("""#!/usr/bin/env python3
import sys,json
def send(value): print(json.dumps(value),flush=True)
def limited():
 send({'type':'rate_limit_event','rate_limit_info':{'status':'rejected','resetsAt':2000000000}})
 send({'type':'result','subtype':'error_during_execution','is_error':True,'result':'Plan limit'})
if '--input-format' not in sys.argv: limited()
else:
 for line in sys.stdin:
  r=json.loads(line)
  if r.get('type')=='control_request': send({'type':'control_response','response':{'subtype':'success','request_id':r['request_id']}})
  elif r.get('type')=='user': limited(); break
""")
            setExecutable(true)
        }
        val request = AgentRequest(AgentSpec(AgentId("writer"), "Writer", RuntimeKind.ClaudeCode, ""), "Work", root.path)
        SupervisedProcessExecutor().use { executor ->
            listOf(ClaudeCodeRuntime(executor, binary.path), ClaudeCodeSessionRuntime(binary.path, scope)).forEach { runtime ->
                val outcome = withTimeout(10_000) { runtime.run(request).toList() }.filterIsInstance<AgentEvent.Finished>().single().outcome
                assertFalse(outcome.ok)
                assertEquals(2000000000000L, outcome.usageLimit?.resetsAtEpochMillis)
            }
        }
    }

    @Test fun codexSendsTheSchemaAndReadsTheRecordedNativeReview() = fixture { root, scope ->
        File(root, "schema.json").writeText(TaskReview.schema(candidate).toString())
        File(root, "result.json").writeText(resource("codex-0.163.0-structured-review.json"))
        val binary = File(root, "codex-fixture").apply {
            writeText("""#!/usr/bin/env python3
import sys,json
def send(value): print(json.dumps(value),flush=True)
for line in sys.stdin:
 r=json.loads(line); method=r.get('method'); ident=r.get('id')
 if method=='initialize': send({'id':ident,'result':{}})
 elif method=='thread/start': send({'id':ident,'result':{'thread':{'id':'review'}}})
 elif method=='turn/start':
  assert r['params']['outputSchema']==json.load(open('schema.json'))
  send({'id':ident,'result':{'turn':{'id':'turn'}}})
  result=json.load(open('result.json')); result['params']['threadId']='review'
  send(result)
""")
            setExecutable(true)
        }
        val request = AgentRequest(AgentSpec(AgentId("review"), "Reviewer", RuntimeKind.Codex, ""), "Review", root.path,
            outputSchema = TaskReview.schema(candidate))
        val outcome = withTimeout(10_000) { CodexAppServerRuntime(binary.path, scope).run(request).toList() }
            .filterIsInstance<AgentEvent.Finished>().single().outcome
        assertTrue(outcome.ok, outcome.failure)
        val review = TaskReview.parse(outcome.text)
        assertEquals(candidate, review.candidate)
        assertEquals("repair", review.verdict)
        assertEquals("Source.kt", review.findings.single().sourcePath)
        assertEquals(1, review.findings.single().line)
    }

    @Test fun claudeBatchAndInteractiveTransportsUseTheSchemaAndStructuredResult() = fixture { root, scope ->
        File(root, "schema.json").writeText(TaskReview.schema(candidate).toString())
        val review = Json.parseToJsonElement(resource("codex-0.163.0-structured-review.json")).jsonObject
            .getValue("params").jsonObject.getValue("turn").jsonObject.getValue("items").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        File(root, "result.json").writeText(buildJsonObject {
            put("type", "result"); put("subtype", "success"); put("is_error", false)
            put("result", "Review is complete; use structured_output")
            put("structured_output", Json.parseToJsonElement(review))
        }.toString())
        val binary = File(root, "claude-fixture").apply {
            writeText("""#!/usr/bin/env python3
import sys,json
assert json.loads(sys.argv[sys.argv.index('--json-schema')+1])==json.load(open('schema.json'))
def send(value): print(json.dumps(value),flush=True)
result=json.load(open('result.json'))
if '--input-format' not in sys.argv: send(result)
else:
 for line in sys.stdin:
  r=json.loads(line)
  if r.get('type')=='control_request':
   send({'type':'control_response','response':{'subtype':'success','request_id':r['request_id']}})
  elif r.get('type')=='user': send(result); break
""")
            setExecutable(true)
        }
        val request = AgentRequest(AgentSpec(AgentId("review"), "Reviewer", RuntimeKind.ClaudeCode, ""), "Review", root.path,
            outputSchema = TaskReview.schema(candidate))
        SupervisedProcessExecutor().use { executor ->
            listOf(ClaudeCodeRuntime(executor, binary.path), ClaudeCodeSessionRuntime(binary.path, scope)).forEach { runtime ->
                val outcome = withTimeout(10_000) { runtime.run(request).toList() }.filterIsInstance<AgentEvent.Finished>().single().outcome
                assertTrue(outcome.ok, outcome.failure)
                assertEquals(TaskReview.parse(review), TaskReview.parse(outcome.text))
            }
        }
    }
}
