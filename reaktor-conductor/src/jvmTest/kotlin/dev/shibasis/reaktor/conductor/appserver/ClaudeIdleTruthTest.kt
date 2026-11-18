package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ClaudeIdleTruthTest {
    @Test fun oneResultCanFinishTwoWrittenUserLines() = fixture(false)
    @Test fun stoppingWithASteerQueuedFinishesOnOneResult() = fixture(true)

    @Test fun aSteeredToolDecisionAfterAResultKeepsTheReaderAlive() = runBlocking {
        val root = Files.createTempDirectory("claude-idle-question").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val script = root.resolve("claude-stub").apply {
                writeText("""#!/usr/bin/env python3
import sys,json
init=json.loads(sys.stdin.readline())
print(json.dumps({"type":"control_response","response":{"subtype":"success","request_id":init["request_id"]}}),flush=True)
sys.stdin.readline()
print(json.dumps({"type":"assistant","message":{"content":[{"type":"text","text":"Working"}]}}),flush=True)
sys.stdin.readline()
print(json.dumps({"type":"result","subtype":"success","result":"First result"}),flush=True)
print(json.dumps({"type":"control_request","request_id":"permission","request":{"subtype":"can_use_tool","tool_name":"Read","input":{"file_path":"Invented.kt"}}}),flush=True)
sys.stdin.readline()
print(json.dumps({"type":"assistant","message":{"content":[{"type":"text","text":"Approved answer"}]}}),flush=True)
print(json.dumps({"type":"result","subtype":"success","result":"Approved answer"}),flush=True)
""")
                setExecutable(true, true)
            }
            val session = ClaudeCodeSessionRuntime(script.path, scope).open(AgentRequest(
                AgentSpec(AgentId("claude"), "Claude", RuntimeKind.ClaudeCode, ""), "First", root.path))
            var steered = false
            val decision = CompletableDeferred<CommandOutcome>()
            try {
                val outcome = withTimeout(4000) {
                    session.events.onEach { event ->
                        if (event is AgentEvent.Delta && !steered) {
                            steered = true
                            assertEquals(CommandOutcome.Accepted, session.steer("Second", session.activeTurn))
                        }
                        if (event is AgentEvent.RequestPending) launch {
                            delay(750)
                            decision.complete(session.resolve(event.request.id, AgentDecision.Approve))
                        }
                    }.filterIsInstance<AgentEvent.Finished>().first()
                }
                assertEquals(CommandOutcome.Accepted, decision.await())
                assertTrue(outcome.outcome.text.contains("Approved answer"))
            } finally { session.close() }
        } finally { scope.cancel(); root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    private fun fixture(stop: Boolean) = runBlocking {
        val root = Files.createTempDirectory("claude-idle").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val script = root.resolve("claude-stub").apply {
                writeText("""#!/usr/bin/env python3
import sys,json,time
for line in sys.stdin:
 v=json.loads(line)
 if v.get('type')=='control_request':
  print(json.dumps({'type':'control_response','response':{'request_id':v['request_id'],'subtype':'success'}}),flush=True)
  if v.get('request',{}).get('subtype')=='interrupt':
   print(json.dumps({'type':'result','result':'Stopped','is_error':False}),flush=True)
 elif v.get('type')=='user':
  if 'first' not in globals():
   first=True
   print(json.dumps({'type':'assistant','message':{'id':'answer','content':[{'type':'text','text':'An invented answer'}]}}),flush=True)
  elif ${if (stop) "False" else "True"}:
   print(json.dumps({'type':'result','result':'An invented answer','is_error':False}),flush=True)
""")
                setExecutable(true, true)
            }
            val session = ClaudeCodeSessionRuntime(script.path, scope).open(AgentRequest(
                AgentSpec(AgentId("claude"), "Claude", RuntimeKind.ClaudeCode, ""), "First", root.path))
            var steered = false
            val stopped = CompletableDeferred<CommandOutcome>()
            try {
                val result = withTimeout(3000) {
                    session.events.onEach { event ->
                        if (event is AgentEvent.Delta && !steered) {
                            steered = true
                            assertEquals(CommandOutcome.Accepted, session.steer("Second", session.activeTurn))
                            if (stop) launch { stopped.complete(session.interrupt()) }
                        }
                    }.filterIsInstance<AgentEvent.Finished>().first()
                }
                assertTrue(steered)
                if (stop) { assertEquals(CommandOutcome.Accepted, stopped.await()); assertTrue(result.outcome.interrupted) } else assertEquals("An invented answer", result.outcome.text)
            } finally { session.close() }
        } finally { scope.cancel(); root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
