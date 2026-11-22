package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ClaudeQueueTruthTest {
    @Test fun aResultDoesNotCloseTheProcessWhileASteeredMessageIsQueued() = runBlocking {
        fixture { runtime, directory ->
            val session = runtime.open(AgentRequest(AgentSpec(AgentId("claude"), "Claude", RuntimeKind.ClaudeCode, ""), "First", directory.path))
            var sent = false
            try {
                val events = withTimeout(4000) { session.events.onEach { event ->
                    if (event is AgentEvent.Delta && !sent) { sent = true; assertEquals(CommandOutcome.Accepted, session.steer("Second", session.activeTurn)) }
                }.toList() }
                assertTrue(sent)
                assertEquals("First answerSecond answer", events.filterIsInstance<AgentEvent.Finished>().single().outcome.text)
            } finally { session.close() }
        }
    }

    @Test fun cancellingTheReaderDoesNotWaitForAnUnreadInterruptResponse() = runBlocking {
        fixture { runtime, directory ->
            val session = runtime.open(AgentRequest(AgentSpec(AgentId("claude"), "Claude", RuntimeKind.ClaudeCode, ""), "Wait", directory.path))
            val started = CompletableDeferred<Unit>()
            val job = launch { session.events.collect { if (it is AgentEvent.TurnStarted) started.complete(Unit) } }
            try {
                withTimeout(3000) { started.await() }
                job.cancelAndJoin()
                withTimeout(300) { session.interrupt() }
            } finally { session.close() }
        }
    }

    private suspend fun fixture(test: suspend CoroutineScope.(ClaudeCodeSessionRuntime, File) -> Unit) = coroutineScope {
        val root = Files.createTempDirectory("claude-queue").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val script = root.resolve("claude-stub").apply { writeText("""#!/usr/bin/python3
import sys,json
n=0
for line in sys.stdin:
 row=json.loads(line)
 if row.get('type')=='control_request':
  if row['request']['subtype']=='initialize':
   print(json.dumps({'type':'control_response','response':{'subtype':'success','request_id':row['request_id']}}),flush=True)
 elif row.get('type')=='user':
  text=row['message']['content'][0]['text']
  if text=='Wait': continue
  n+=1
  result='First answer' if n==1 else 'Second answer'
  print(json.dumps({'type':'assistant','message':{'content':[{'type':'text','text':result}]}}),flush=True)
  if n==2:
   print(json.dumps({'type':'result','subtype':'success','result':'First answer'}),flush=True)
   print(json.dumps({'type':'result','subtype':'success','result':result}),flush=True)
"""); setExecutable(true, true) }
            test(ClaudeCodeSessionRuntime(script.path, scope), root)
        } finally { scope.cancel(); root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
