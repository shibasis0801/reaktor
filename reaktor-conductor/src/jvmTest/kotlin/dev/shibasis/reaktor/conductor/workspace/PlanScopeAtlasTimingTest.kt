package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.AgentEvent
import dev.shibasis.reaktor.conductor.AgentRequest
import dev.shibasis.reaktor.conductor.AgentRuntime
import dev.shibasis.reaktor.conductor.ProviderCapability
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlanScopeAtlasTimingTest {
    @Test fun measuresRealAtlasFallbackAndItsConfirmedCacheWhenAtlasIsReady() = runBlocking {
        val bridge = System.getenv("HANGAR_SCOPE_ATLAS_BRIDGE") ?: return@runBlocking
        val output = File(requireNotNull(System.getenv("HANGAR_SCOPE_ATLAS_METRICS")))
        val root = Files.createTempDirectory("plan-atlas-measure").toFile()
        val process = ProcessBuilder("/usr/bin/python3", "-u", bridge).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val input = process.inputStream.bufferedReader()
        val writer = process.outputStream.bufferedWriter()
        val calls = ConcurrentLinkedQueue<JsonObject>()
        val results = ArrayList<JsonObject>()
        val owner = BoardActor(BoardActorKind.Person, "Fixture owner")
        val actor = BoardActor(BoardActorKind.Agent, "Fixture agent", "codex:atlas-timing")
        val profile = AgentProfile("writer", "Fixture writer", RuntimeKind.Echo, access = TaskAccess.Write)
        val thread = "hangar:atlas-timing"
        val io = Any()
        suspend fun read(name: String, args: JsonObject): JsonElement = synchronized(io) {
            val start = System.nanoTime()
            writer.write(buildJsonObject { put("name", name); put("args", args) }.toString())
            writer.newLine(); writer.flush()
            Json.parseToJsonElement(assertNotNull(input.readLine())).jsonObject.also { answer ->
                calls += buildJsonObject {
                    put("name", name); put("page", args["page"] ?: JsonNull); put("chars", args["chars"] ?: JsonNull)
                    put("ms", (System.nanoTime() - start) / 1_000_000.0)
                    put("ready", answer["ready"] ?: JsonNull); put("pages", answer["pages"] ?: JsonNull)
                }
            }
        }
        fun seed(store: WorkPlanStore) {
            assertTrue(store.write(thread, PlanPatch(title = "Timing", ops = listOf(
                PlanOp.Add("Engine", key = "engine", refs = listOf("module:modules/engine")))), owner).refused.isEmpty())
        }
        suspend fun measured(name: String, block: suspend () -> Unit) {
            val before = calls.size
            val start = System.nanoTime()
            block()
            results += buildJsonObject { put("name", name); put("ms", (System.nanoTime() - start) / 1_000_000.0); put("calls", calls.size - before) }
        }
        try {
            PlanRefResolver(root, ::read).use { resolver ->
                WorkPlanStore(root.toPath().resolve("plans"), resolveRefs = resolver::resolve, cachedRefs = resolver::cached).use { store ->
                    seed(store)
                    measured("cold_next") { assertNotNull(store.next(thread, actor) { profile }.candidate) }
                    val loading = PlanRefResolver::class.java.getDeclaredField("loading").apply { isAccessible = true }.get(resolver) as Deferred<*>
                    var loaded: Any? = null
                    measured("background_graph_wait") { loaded = withTimeout(900_000) { loading.await() } }
                    if (loaded != null) {
                    measured("warm_fallback_next") { assertNotNull(store.next(thread, actor) { profile }.candidate) }
                    val probe = assertNotNull(calls.lastOrNull { it["chars"]?.jsonPrimitive?.int == 20_000 })
                    assertEquals(true, probe["ready"]?.jsonPrimitive?.boolean)
                    val before = calls.size
                    measured("confirmed_warm_next") { assertNotNull(store.next(thread, actor) { profile }.candidate) }
                    assertEquals(before, calls.size)
                    } else results += buildJsonObject {
                        put("name", "warm_fallback_unavailable")
                        put("reason", if (calls.lastOrNull()?.get("ready") == JsonPrimitive(false)) "Atlas reported not ready"
                            else "The graph load did not complete; an Atlas page or revision was unavailable")
                        put("calls", calls.size)
                    }
                }
            }
            val runtime = object : AgentRuntime {
                override val kind = RuntimeKind.Echo
                override fun run(request: AgentRequest) = emptyFlow<AgentEvent>()
            }
            val stopped = CoroutineScope(SupervisorJob().apply { cancel() })
            AgentWorkspace(root, root.toPath().resolve("owner"), mapOf(runtime.kind to runtime), scope = stopped,
                discover = { ProviderCapability(it) }, kernelRead = ::read).use { workspace ->
                workspace.profiles.save(profile, owner)
                seed(workspace.plans)
                measured("cold_attach") { assertEquals(AgentIsolation.Worktree, workspace.attachPlan(thread, listOf("engine"), "writer", "", owner).run.isolation) }
            }
        } finally {
            writer.close()
            if (!process.waitFor(6, TimeUnit.SECONDS)) { process.destroy(); assertTrue(process.waitFor(6, TimeUnit.SECONDS)) }
            output.parentFile.mkdirs()
            output.writeText(buildJsonObject { put("results", JsonArray(results)); put("atlas", JsonArray(calls.toList())) }.toString())
            root.deleteTreeSafely(within = root.parentFile)
        }
    }
}
