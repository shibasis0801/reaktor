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
