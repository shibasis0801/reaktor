package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class NativeChildIsolationTest {

    @Test fun hooksRetainNativeSourceFailuresAndChildOwnership() = runBlocking {
        val root = Files.createTempDirectory("native-hook-protocol").toFile()
        val binary = File(root, "app-server-fixture")
        binary.writeText("""#!/usr/bin/env python3
import sys,json
def send(value): print(json.dumps(value),flush=True)
def event(method,params): send({'method':method,'params':params})
def hook(thread,ident,status,source,duration,entries):
 run={'id':ident,'eventName':'stop','status':status,'source':source,'sourcePath':'/hooks/'+source+'.json','durationMs':duration,'entries':entries,'command':'PRIVATE_COMMAND','environment':{'TOKEN':'PRIVATE_TOKEN'}}
 event('hook/started',{'threadId':thread,'turnId':thread+'-turn','run':dict(run,status='running',durationMs=None,entries=[])})
 event('hook/completed',{'threadId':thread,'turnId':thread+'-turn','run':run})
for line in sys.stdin:
 r=json.loads(line); method=r.get('method'); ident=r.get('id')
 if method=='initialize': send({'id':ident,'result':{}})
 elif method=='thread/start': send({'id':ident,'result':{'thread':{'id':'parent'}}})
 elif method=='turn/start':
  send({'id':ident,'result':{'turn':{'id':'parent-turn'}}})
  event('turn/started',{'threadId':'parent','turn':{'id':'parent-turn'}})
  hook('parent','plugin-timeout','failed','plugin',10000,[{'kind':'error','text':'hook timed out after 10s','unknown':'PRIVATE_FIELD'},{'kind':'context','text':'x'*17000}])
  hook('parent','user-success','completed','user',8,[])
  hook('parent','plugin-block','blocked','plugin',5,[{'kind':'feedback','text':'[keep-working] continue the shared plan'}])
  event('item/completed',{'threadId':'parent','item':{'type':'collabAgentToolCall','id':'spawn','senderThreadId':'parent','receiverThreadIds':['child'],'agentsStates':{'child':{'status':'running'}}}})
  hook('child','child-stop','completed','plugin',4,[])
  hook('unknown','unowned-stop','failed','plugin',1,[{'kind':'error','text':'UNOWNED'}])
  event('turn/completed',{'threadId':'parent','turn':{'id':'parent-turn','status':'completed','items':[{'type':'agentMessage','text':'PARENT ANSWER'}]}})
""")
        binary.setExecutable(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val events = withTimeout(10000) {
                CodexAppServerRuntime(binary.path, scope).run(AgentRequest(
                    AgentSpec(AgentId("lead"), "Lead", RuntimeKind.Codex, ""), "task", root.path)).toList()
            }
            val hooks = events.filterIsInstance<AgentEvent.Activity>().map { it.item }.filter { it.id.startsWith("hook:") }
            assertEquals(8, hooks.size)
            assertTrue(hooks.all { it.input.orEmpty().length <= 6000 && it.output.orEmpty().length <= 16000 })
            val finished = hooks.filter { it.status != ActivityStatus.Started }.associateBy { it.id }
            assertEquals(ActivityStatus.Failed, finished.getValue("hook:plugin-timeout").status)
            assertTrue(finished.getValue("hook:plugin-timeout").output.orEmpty().contains("hook timed out after 10s"))
            assertTrue(finished.getValue("hook:plugin-timeout").output.orEmpty().contains("10000"))
            assertTrue(finished.getValue("hook:plugin-timeout").input.orEmpty().contains("/hooks/plugin.json"))
            assertEquals(ActivityStatus.Completed, finished.getValue("hook:user-success").status)
            assertEquals(ActivityStatus.Waiting, finished.getValue("hook:plugin-block").status)
            assertTrue(finished.getValue("hook:plugin-block").output.orEmpty().contains("[keep-working]"))
            assertEquals("native:child", finished.getValue("hook:child-stop").parentId)
            assertTrue(hooks.none { "PRIVATE_" in it.input.orEmpty() + it.output.orEmpty() || "UNOWNED" in it.output.orEmpty() })
            val store = dev.shibasis.reaktor.conductor.workspace.AgentActivityStore(root.resolve("activity").toPath())
            val runId = "a".repeat(64)
            hooks.forEach { store.append(runId, "lead", 1, it) }
            val retained = store.page(runId).records.map { it.item }
            assertEquals(hooks.map { it.id to it.status }, retained.map { it.id to it.status })
            val timeout = retained.single { it.id == "hook:plugin-timeout" && it.status == ActivityStatus.Failed }
            assertTrue(timeout.detailTruncated)
            assertEquals(16000, timeout.output?.length)
            assertTrue(timeout.output.orEmpty().contains("hook timed out after 10s"))
            assertEquals("PARENT ANSWER", events.filterIsInstance<AgentEvent.Finished>().single().outcome.text)
        } finally {
            scope.cancel()
            root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
        Unit
    }
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
        } finally { scope.cancel(); root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
        Unit
    }
}
