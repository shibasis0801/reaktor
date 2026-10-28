package dev.shibasis.reaktor.conductor.appserver

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlin.test.*

class CodexAccountTest {
    @Test fun preservesIndependentBucketsAndUnknownValues() {
        val snapshot = parseCodexAccount(null, Json.parseToJsonElement("""{
          "ordinaryUsageAllowed":false,
          "rateLimits":{"primary":{"usedPercent":99}},
          "rateLimitsByLimitId":{
            "codex":{"planType":"pro","primary":{"usedPercent":35,"windowDurationMins":300,"resetsAt":2000000000},"secondary":null},
            "special":{"limitName":"Special allowance","normalModelSlug":"special-model","primary":{"usedPercent":110},"credits":{"balance":"12.5","unlimited":false}},
            "unknown":{}
          }
        }""").jsonObject, 42)
        assertNull(snapshot.models)
        assertEquals(false, snapshot.ordinaryUsageAllowed)
        val buckets = snapshot.buckets!!
        assertEquals(listOf("codex", "special", "unknown"), buckets.map { it.id })
        assertEquals(35.0, buckets[0].limits.windows.single().usedPercent)
        assertEquals(2000000000000L, buckets[0].limits.windows.single().resetsAt)
        assertEquals("pro", buckets[0].limits.plan)
        assertEquals("special-model", buckets[1].model)
        assertEquals("12.5", buckets[1].creditBalance)
        assertNull(buckets[1].limits.windows.single().resetsAt)
        assertTrue(buckets[2].limits.windows.isEmpty())
        assertNull(buckets[2].unlimitedCredits)
        assertNull(parseCodexAccount(null, null, 0).buckets)
        val legacy = parseCodexAccount(null, Json.parseToJsonElement("""{"rateLimits":{"primary":{"usedPercent":0}}}""").jsonObject, 1)
        assertEquals(0.0, legacy.buckets!!.single().limits.windows.single().usedPercent)
    }

    @Test fun readsPaginatedCatalogAndLimitsWithoutStartingInference() = runBlocking {
        val root = Files.createTempDirectory("hangar-account-test").toFile()
        try {
            val binary = root.resolve("codex-fixture").apply {
                writeText("""#!/usr/bin/env python3
import sys,json
for line in sys.stdin:
 r=json.loads(line); m=r.get('method'); p=r.get('params',{})
 if m=='initialized': continue
 if m=='initialize': result={}
 elif m=='account/rateLimits/read': result={'rateLimits':{'primary':{'usedPercent':25,'windowDurationMins':300}}}
 elif m=='model/list':
  if not p.get('cursor'): result={'data':[{'id':'display-id','model':'actual-model','displayName':'Actual model','isDefault':True,'defaultReasoningEffort':'high','supportedReasoningEfforts':[{'reasoningEffort':'high'}]}],'nextCursor':'page2'}
  else: result={'data':[{'model':'hidden-model','hidden':True},{'model':'second-model','supportedReasoningEfforts':[]}],'nextCursor':None}
 else:
  print(json.dumps({'id':r['id'],'error':{'code':-1,'message':'Unexpected inference or mutation request: '+m}}),flush=True); continue
 print(json.dumps({'id':r['id'],'result':result}),flush=True)
""")
                setExecutable(true)
            }
            val snapshot = readCodexAccount(binary.path, root)
            assertNull(snapshot.modelsError)
            assertNull(snapshot.usageError)
            assertEquals(listOf("actual-model", "second-model"), snapshot.models!!.map { it.id })
            assertEquals("actual-model", snapshot.defaultModel)
            assertEquals(listOf("high"), snapshot.models!![0].effort!!.supported!!.map { it.value })
            assertTrue(snapshot.models!![1].effort!!.supported!!.isEmpty())
            assertEquals(25.0, snapshot.buckets!!.single().limits.windows.single().usedPercent)
        } finally { root.deleteTreeSafely(root.parentFile) }
    }
}
