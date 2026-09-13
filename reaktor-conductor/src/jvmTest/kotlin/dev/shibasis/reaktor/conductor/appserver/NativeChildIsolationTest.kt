package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class NativeChildIsolationTest {
    @Test fun childCompletionCannotCompleteParentAndControlsUseChildTurn() = runBlocking {
        val root = Files.createTempDirectory("native-child-protocol").toFile()
        val binary = File(root, "app-server-fixture")
        binary.writeText("""#!/usr/bin/env python3
import sys,json
def send(value): print(json.dumps(value),flush=True)
def event(method,params): send({'method':method,'params':params})
for line in sys.stdin:
 r=json.loads(line); method=r.get('method'); ident=r.get('id')
 if method=='initialize': send({'id':ident,'result':{}})
 elif method=='thread/start': send({'id':ident,'result':{'thread':{'id':'parent'}}})
 elif method=='turn/start':
  send({'id':ident,'result':{'turn':{'id':'parent-turn'}}})
  event('turn/started',{'threadId':'parent','turn':{'id':'parent-turn'}})
  event('item/completed',{'threadId':'parent','item':{'type':'collabAgentToolCall','id':'spawn','senderThreadId':'parent','receiverThreadIds':['child'],'agentsStates':{'child':{'status':'running'}}}})
  event('turn/started',{'threadId':'child','turn':{'id':'child-turn'}})
  event('item/agentMessage/delta',{'threadId':'child','delta':'WRONG CHILD OUTPUT'})
 elif method=='turn/interrupt':
  assert r['params']=={'threadId':'child','turnId':'child-turn'},r
  send({'id':ident,'result':{}})
  event('turn/completed',{'threadId':'child','turn':{'id':'child-turn','status':'completed','items':[{'type':'agentMessage','text':'WRONG'}]}})
  event('turn/completed',{'threadId':'parent','turn':{'id':'parent-turn','status':'completed','items':[{'type':'agentMessage','text':'PARENT ANSWER'}]}})
""")
        binary.setExecutable(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val runtime = CodexAppServerRuntime(binary.path, scope)
            runtime.open(AgentRequest(AgentSpec(AgentId("lead"), "Lead", RuntimeKind.Codex, ""), "task", root.path)).use { session ->
                val events = async { session.events.toList() }
                withTimeout(10000) { while (session.nativeAgents().none { it.turnId == "child-turn" }) delay(10) }
                assertEquals("parent-turn", session.activeTurn)
                assertIs<CommandOutcome.Unsupported>(session.controlNative("unowned", "child-turn", null))
                assertIs<CommandOutcome.Stale>(session.controlNative("child", "old-turn", null))
                assertIs<CommandOutcome.Accepted>(session.controlNative("child", "child-turn", null))
                val output = withTimeout(10000) { events.await() }
                assertEquals("PARENT ANSWER", output.filterIsInstance<AgentEvent.Finished>().single().outcome.text)
                assertTrue(output.filterIsInstance<AgentEvent.Delta>().none { it.text.contains("WRONG") })
            }
        } finally { scope.cancel(); root.deleteRecursively() }
        Unit
    }
}
