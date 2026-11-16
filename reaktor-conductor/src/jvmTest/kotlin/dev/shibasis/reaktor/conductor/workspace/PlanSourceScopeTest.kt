package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import kotlin.test.*

class PlanSourceScopeTest {
    private val owner = BoardActor(BoardActorKind.Person, "Owner")
    private val firstAgent = BoardActor(BoardActorKind.Agent, "First", "codex:first")
    private val secondAgent = BoardActor(BoardActorKind.Agent, "Second", "codex:second")
    private val thread = "hangar:scope"

    private class Atlas(val root: File) {
        val calls = Collections.synchronizedList(ArrayList<String>())
        var monitor: Any? = null
        var workspaceMonitor: Any? = null
        val missingNames = HashSet<String>()
        var ready = true
        var missing = false
        var delayMillis = 0L
        var pageCount = 2
        val fullPages = ConcurrentLinkedQueue<Int>()
        val pagesFinished = CompletableDeferred<Unit>()
        suspend fun read(name: String, args: JsonObject): JsonElement {
            assertFalse(monitor?.let(Thread::holdsLock) == true)
            assertFalse(workspaceMonitor?.let(Thread::holdsLock) == true)
            yield()
            delay(delayMillis)
            if (name == "atlas_graph" && args["chars"]?.jsonPrimitive?.int == 250_000) {
                val index = args.getValue("page").jsonPrimitive.int
                fullPages.add(index)
                if (index == pageCount - 1) pagesFinished.complete(Unit)
            }
            val key = args["path"] ?: args["name"] ?: args["page"]
            calls += "$name:$key:${args["chars"]}"
            return when (name) {
                "atlas_read" -> if (missing || args["name"]?.jsonPrimitive?.content in missingNames) error("Unknown symbol") else buildJsonObject {
                    val path = args["path"]?.jsonPrimitive?.content ?: if (args["name"]?.jsonPrimitive?.content == "Other") "src/other.kt" else "src/shared.kt"
                    put("path", File(root, path).canonicalPath)
                    putJsonArray("next") { add(buildJsonObject { put("op", "atlas.query") }) }
                }
                "atlas_graph" -> buildJsonObject {
                    put("ready", ready); put("revision", "fixture-revision"); put("pages", pageCount)
                    if (args.getValue("page").jsonPrimitive.int == 1) assertEquals("fixture-revision", args.getValue("revision").jsonPrimitive.content)
                    putJsonObject("shape") { putJsonArray("nodes") {
                        add(buildJsonObject { putJsonObject("attributes") {
                            put("path", if (args.getValue("page").jsonPrimitive.int == 0) "src/shared.kt" else "src/other.kt")
                            put("module", if (args.getValue("page").jsonPrimitive.int == 0) "core" else "other")
                        } })
                    } }
                }
                else -> error("Unexpected kernel read")
            }
        }
    }

    private fun fixture(kernel: Boolean = true, block: suspend (AgentWorkspace, Atlas) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("plan-scope").toFile()
        val atlas = Atlas(root)
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Echo
            override fun run(request: AgentRequest) = emptyFlow<AgentEvent>()
        }
        val stopped = CoroutineScope(SupervisorJob().apply { cancel() })
        try {
            AgentWorkspace(root, root.toPath().resolve("state"), mapOf(runtime.kind to runtime), maxActive = 8, scope = stopped,
                discover = { ProviderCapability(it) }, kernelRead = if (kernel) atlas::read else null).use { workspace ->
                atlas.monitor = workspace.plans
                atlas.workspaceMonitor = AgentWorkspace::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(workspace)
                workspace.profiles.save(AgentProfile("writer", "Writer", RuntimeKind.Echo, access = TaskAccess.Write), owner)
                block(workspace, atlas)
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    private fun seed(workspace: AgentWorkspace, second: String = "symbol:Shared") {
        val applied = workspace.plans.write(thread, PlanPatch(title = "Scope", ops = listOf(
            PlanOp.Add("Component", key = "component", kind = PlanKind.Component, refs = listOf("module:core")),
            PlanOp.Add("Module work", key = "module", parent = "component", refs = listOf("module:core")),
            PlanOp.Add("Symbol work", key = "symbol", refs = listOf(second)),
            PlanOp.Add("Free file", key = "free", refs = listOf("file:src/free.kt")),
        )), owner)
        assertTrue(applied.refused.isEmpty())
    }

    @Test fun linkOnlyRefsInheritTheNearestSourceScope() = fixture(kernel = false) { workspace, atlas ->
        workspace.plans.write(thread, PlanPatch(title = "Inherited code scope", ops = listOf(
            PlanOp.Add("Component", key = "component", kind = PlanKind.Component, refs = listOf("file:src/shared.kt")),
            PlanOp.Add("Linked leaf", key = "linked", parent = "component", refs = listOf("unit:u1")),
            PlanOp.Add("Held", key = "held", refs = listOf("file:src/shared.kt")),
            PlanOp.Status("held", PlanStatus.Doing),
        )), firstAgent)
        assertNull(workspace.plans.next(thread, secondAgent, item = "linked") { workspace.profiles.read("writer") }.candidate)
        assertTrue(atlas.calls.isEmpty())
        assertEquals(AgentIsolation.Worktree, workspace.attachPlan(thread, listOf("linked"), "writer", "", owner).run.isolation)
    }

    @Test fun sharedLinkRefsNeverConflictOrReachTheResolver() = fixture { workspace, atlas ->
        val links = listOf("source:reading", "unit:u1", "skill:check", "url:https://example.invalid", "person:owner")
        workspace.plans.write(thread, PlanPatch(title = "Shared reading", ops = listOf(
            PlanOp.Add("Held", key = "held", refs = listOf("file:src/held.kt") + links),
            PlanOp.Add("Ready", key = "ready", refs = listOf("file:src/ready.kt") + links),
            PlanOp.Status("held", PlanStatus.Doing),
        )), firstAgent)
        assertEquals("ready", workspace.plans.next(thread, secondAgent, item = "ready") { workspace.profiles.read("writer") }.candidate?.item?.id)
        assertFalse(AgentSourceScope.conflicts(links, links))
        assertTrue(atlas.calls.isEmpty())
        assertEquals(AgentIsolation.Shared, workspace.attachPlan(thread, listOf("ready"), "writer", "", owner).run.isolation)
    }

    @Test fun nextSharesOneDeadlineAcrossThreeChangingScopeRounds() = changingScopeDeadline(false)

    @Test fun attachSharesOneDeadlineAcrossThreeChangingScopeRounds() = changingScopeDeadline(true)

    private fun changingScopeDeadline(attach: Boolean) {
        val root = Files.createTempDirectory("scope-changing-deadline").toFile()
        val stopped = CoroutineScope(SupervisorJob().apply { cancel() })
        val executor = Executors.newSingleThreadExecutor()
        val reads = AtomicInteger()
        lateinit var workspace: AgentWorkspace
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Echo
            override fun run(request: AgentRequest) = emptyFlow<AgentEvent>()
        }
        try {
            workspace = AgentWorkspace(root, root.toPath().resolve("state"), mapOf(runtime.kind to runtime), scope = stopped,
                discover = { ProviderCapability(it) }, kernelRead = { _, _ ->
                    val round = reads.incrementAndGet()
                    workspace.plans.write(thread, PlanPatch(ops = listOf(
                        PlanOp.Add("New scope", key = "late$round", parent = "component", refs = listOf("symbol:Late$round")),
                    )), owner)
                    delay(2_000)
                    buildJsonObject { put("path", "src/round$round.kt") }
                })
            workspace.profiles.save(AgentProfile("writer", "Writer", RuntimeKind.Echo, access = TaskAccess.Write), owner)
            workspace.plans.write(thread, PlanPatch(title = "Deadline", ops = listOf(
                PlanOp.Add("Component", key = "component", kind = PlanKind.Component),
                PlanOp.Add("Start", key = "start", parent = "component", refs = listOf("symbol:Start")),
            )), owner)
            val start = System.nanoTime()
            executor.submit {
                runBlocking {
                    if (attach) assertEquals(AgentIsolation.Worktree, workspace.attachPlan(thread, listOf("component"), "writer", "", owner).run.isolation)
                    else assertNotNull(workspace.plans.next(thread, firstAgent) { workspace.profiles.read("writer") }.candidate)
                }
            }.get(4_500, TimeUnit.MILLISECONDS)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            assertTrue(elapsed < 4_000, "Changing scope request took ${elapsed}ms")
            assertNotNull(workspace.plans.plan(thread)?.item("late2"))
            println("Changing scope ${if (attach) "attach" else "next"}: ${elapsed}ms")
        } finally {
            runCatching { workspace.close() }
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            root.deleteTreeSafely(within = root.parentFile)
        }
    }

    @Test fun nextStopsWaitingWhileFiftySlowPagesContinueAndWarmTheNextCall() = fixture { workspace, atlas ->
        workspace.plans.write(thread, PlanPatch(title = "Bounded next", ops = listOf(
            PlanOp.Add("Module", key = "module", refs = listOf("module:core")),
        )), owner)
        atlas.pageCount = 50
        atlas.delayMillis = 200
        val executor = Executors.newSingleThreadExecutor()
        try {
            repeat(2) {
                val start = System.nanoTime()
                val result = executor.submit<PlanNext> {
                    workspace.plans.next(thread, firstAgent, item = "module") { workspace.profiles.read("writer") }
                }.get(4_500, TimeUnit.MILLISECONDS)
                assertEquals("module", result.candidate?.item?.id)
                println("Bounded next fixture call ${it + 1}: ${(System.nanoTime() - start) / 1_000_000}ms")
            }
            assertEquals(1, atlas.fullPages.count { it == 0 })
            withTimeout(8_000) { atlas.pagesFinished.await() }
            val warmStart = System.nanoTime()
            assertEquals("module", workspace.plans.next(thread, firstAgent, item = "module") { workspace.profiles.read("writer") }.candidate?.item?.id)
            assertTrue((System.nanoTime() - warmStart) / 1_000_000 < 1_500)
            assertEquals((0 until 50).toList(), atlas.fullPages.toList())
            val warm = workspace.attachPlan(thread, listOf("module"), "writer", "", owner)
            assertEquals(AgentIsolation.Shared, warm.run.isolation)
            assertEquals(listOf("src/shared.kt"), workspace.submission(warm.run.id).scope)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun coldAttachmentStopsWaitingAndUsesAWorktreeWhileAtlasLoads() = fixture { workspace, atlas ->
        workspace.plans.write(thread, PlanPatch(title = "Bounded attach", ops = listOf(
            PlanOp.Add("Module", key = "module", refs = listOf("module:core")),
        )), owner)
        atlas.pageCount = 50
        atlas.delayMillis = 200
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<PlanAttached> { runBlocking { workspace.attachPlan(thread, listOf("module"), "writer", "", owner) } }
                .get(4_500, TimeUnit.MILLISECONDS)
            assertEquals(AgentIsolation.Worktree, result.run.isolation)
            assertTrue(workspace.submission(result.run.id).scope.isEmpty())
            assertEquals(1, atlas.fullPages.count { it == 0 })
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun malformedFileRefsKeepRawFootprintsAndDoNotStopNext() = fixture(kernel = false) { workspace, _ ->
        val bad = "file:a\u0000b"
        val plan = workspace.plans.write(thread, PlanPatch(title = "Paths", ops = listOf(
            PlanOp.Add("Invalid path", key = "invalid", refs = listOf(bad)),
            PlanOp.Add("Normal path", key = "normal", refs = listOf("file:src/normal.kt")),
        )), owner).plan
        for (item in listOf("invalid", "normal")) {
            assertEquals(item, workspace.plans.next(thread, secondAgent, item = item) { AgentProfile("paths", "Paths", RuntimeKind.Echo) }.candidate?.item?.id)
        }
        assertEquals(listOf(bad), AgentSourceScope.footprint(plan, requireNotNull(plan.item("invalid")), emptyMap()))
    }

    @Test fun malformedFileRefsUseAWorktreeAndLeaveNormalAttachmentAvailable() = fixture(kernel = false) { workspace, _ ->
        workspace.profiles.save(AgentProfile("paths", "Paths", RuntimeKind.Echo, access = TaskAccess.Write), owner)
        workspace.plans.write(thread, PlanPatch(title = "Paths", ops = listOf(
            PlanOp.Add("Invalid path", key = "invalid", refs = listOf("file:a\u0000b")),
            PlanOp.Add("Normal path", key = "normal", refs = listOf("file:src/normal.kt")),
        )), owner)
        val invalid = workspace.attachPlan(thread, listOf("invalid"), "paths", "", owner)
        assertEquals(AgentIsolation.Worktree, invalid.run.isolation)
        assertTrue(workspace.submission(invalid.run.id).scope.isEmpty())
        assertEquals(AgentIsolation.Shared, workspace.attachPlan(thread, listOf("normal"), "paths", "", owner).run.isolation)
    }

    @Test fun admissionReadsOwnedRunsOnlyWhileHoldingTheWorkspaceLock() = fixture(kernel = false) { workspace, _ ->
        workspace.profiles.save(AgentProfile("paths", "Paths", RuntimeKind.Echo, access = TaskAccess.Write), owner)
        workspace.plans.write(thread, PlanPatch(title = "Paths", ops = listOf(
            PlanOp.Add("Normal path", key = "normal", refs = listOf("file:src/normal.kt")),
        )), owner)
        val lock = AgentWorkspace::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(workspace)
        val field = AgentWorkspace::class.java.getDeclaredField("active").apply { isAccessible = true }
        val original = field.get(workspace)
        var reads = 0
        val backing = mutableMapOf<String, Job>()
        val guarded = object : MutableMap<String, Job> by backing {
            override val keys: MutableSet<String> get() {
                if (Thread.currentThread().stackTrace.any { it.methodName == "ownedRuns" }) {
                    reads++
                    assertTrue(Thread.holdsLock(lock))
                }
                return backing.keys
            }
        }
        try {
            field.set(workspace, guarded)
            workspace.attachPlan(thread, listOf("normal"), "paths", "", owner)
            assertTrue(reads > 0)
        } finally { field.set(workspace, original) }
    }

    @Test fun attachmentConflictsWithADoingItemHeldInAnotherAgentsTerminal() = fixture(kernel = false) { workspace, _ ->
        workspace.profiles.save(AgentProfile("paths", "Paths", RuntimeKind.Echo, access = TaskAccess.Write), owner)
        workspace.plans.write(thread, PlanPatch(title = "Paths", ops = listOf(
            PlanOp.Add("Terminal work", key = "terminal", refs = listOf("file:src/shared.kt")),
            PlanOp.Add("Seat work", key = "seat", refs = listOf("file:src/shared.kt")),
        )), owner)
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Status("terminal", PlanStatus.Doing))), firstAgent)
        assertNotNull(workspace.plans.plan(thread)?.item("terminal")?.lease)
        val attached = workspace.attachPlan(thread, listOf("seat"), "paths", "", owner)
        assertEquals(AgentIsolation.Worktree, attached.run.isolation)
    }

    @Test fun aCompetitorAddedDuringTheLastResolutionRoundUsesAWorktree() = runBlocking {
        val root = Files.createTempDirectory("scope-competitor-race").toFile()
        val stopped = CoroutineScope(SupervisorJob().apply { cancel() })
        var round = 0
        lateinit var workspace: AgentWorkspace
        try {
            val runtime = object : AgentRuntime {
                override val kind = RuntimeKind.Echo
                override fun run(request: AgentRequest) = emptyFlow<AgentEvent>()
            }
            workspace = AgentWorkspace(root, root.toPath().resolve("owner"), mapOf(runtime.kind to runtime), scope = stopped,
                discover = { ProviderCapability(it) }, kernelRead = { _, args ->
                    round++
                    assertFalse(Thread.holdsLock(workspace.plans))
                    val symbol = if (round == 3) "Foo" else "Late$round"
                    workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Add("Late competitor", key = "late$round", refs = listOf("symbol:$symbol")))), owner)
                    buildJsonObject { put("path", if (args["name"]?.jsonPrimitive?.content == "Seat") "src/seat.kt" else "src/late$round.kt") }
                })
            workspace.profiles.save(AgentProfile("paths", "Paths", RuntimeKind.Echo, access = TaskAccess.Write), owner)
            workspace.plans.write(thread, PlanPatch(title = "Paths", ops = listOf(
                PlanOp.Add("Seat work", key = "seat", refs = listOf("symbol:Seat")),
            )), owner)
            val attached = workspace.attachPlan(thread, listOf("seat"), "paths", "", owner)
            assertEquals(3, round)
            assertEquals(AgentIsolation.Worktree, attached.run.isolation)
        } finally {
            runCatching { workspace.close() }
            root.deleteTreeSafely(within = root.parentFile)
        }
    }

    @Test fun anUnknownThreadNamesTheMissingPlanBeforeAttachment() = fixture(kernel = false) { workspace, _ ->
        workspace.profiles.save(AgentProfile("paths", "Paths", RuntimeKind.Echo, access = TaskAccess.Write), owner)
        val missing = "claude:missing-plan"
        val failure = assertFailsWith<IllegalStateException> { workspace.attachPlan(missing, listOf("seat"), "paths", "", owner) }
        assertEquals("$missing has no plan to attach an agent to.", failure.message)
    }

    @Test fun moduleAndSymbolResolveToTheSameFilesAndOverlapUsesWorktrees() = fixture { workspace, atlas ->
        seed(workspace)
        val first = workspace.attachPlan(thread, listOf("module"), "writer", "", owner)
        assertEquals(listOf("src/shared.kt"), workspace.submission(first.run.id).scope)
        assertEquals(AgentIsolation.Worktree, first.run.isolation)
        assertEquals(atlas.calls.size, atlas.calls.distinct().size)
        atlas.calls.clear()
        val second = workspace.attachPlan(thread, listOf("symbol"), "writer", "", owner)
        assertEquals(listOf("src/shared.kt"), workspace.submission(second.run.id).scope)
        assertEquals(AgentIsolation.Worktree, second.run.isolation)
        assertEquals(atlas.calls.size, atlas.calls.distinct().size)
    }

    @Test fun disjointModuleSymbolAndFileItemsShareTheCheckout() = fixture { workspace, _ ->
        seed(workspace, "symbol:Other")
        for ((item, path) in listOf("module" to "src/shared.kt", "symbol" to "src/other.kt", "free" to "src/free.kt")) {
            val attached = workspace.attachPlan(thread, listOf(item), "writer", "", owner)
            assertEquals(listOf(path), workspace.submission(attached.run.id).scope)
            assertEquals(AgentIsolation.Shared, attached.run.isolation)
        }
    }

    @Test fun theStoreRejectsTheSameResolvedConflictAsAdmissionOutsideItsMonitor() = fixture { workspace, atlas ->
        seed(workspace)
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Status("module", PlanStatus.Doing))), firstAgent)
        atlas.delayMillis = 15
        val coldStart = System.nanoTime()
        val blocked = workspace.plans.next(thread, secondAgent, item = "symbol") { workspace.profiles.read("writer") }
        val coldMillis = (System.nanoTime() - coldStart) / 1_000_000
        assertNull(blocked.candidate)
        assertEquals(4, atlas.calls.size)
        val warmStart = System.nanoTime()
        assertEquals("free", workspace.plans.next(thread, secondAgent, item = "free") { workspace.profiles.read("writer") }.candidate?.item?.id)
        val warmMillis = (System.nanoTime() - warmStart) / 1_000_000
        assertEquals(5, atlas.calls.size)
        assertEquals(4, atlas.calls.distinct().size)
        println("next fixture: cold 4 Atlas calls in ${coldMillis}ms; warm 1 Atlas call in ${warmMillis}ms")
    }

    @Test fun theMcpAttachHandlerBlocksOnlyItsCallerWhileColdModulesPageTheGraph() = fixture { workspace, atlas ->
        workspace.plans.write(thread, PlanPatch(title = "MCP scope", ops = listOf(
            PlanOp.Add("Module", key = "module", refs = listOf("module:core")),
        )), owner)
        atlas.delayMillis = 15
        val handler = agentPlanTools(workspace).single { it.name == "agent_plan_attach" }
        val args = buildJsonObject {
            put("thread", thread); put("profile", "writer")
            putJsonArray("items") { add("module") }
            put("by", AgentWorkspaceJson.encodeToJsonElement(BoardActor.serializer(), owner))
        }
        val caller = Thread.currentThread()
        val coldStart = System.nanoTime()
        handler.invoke(args)
        val coldMillis = (System.nanoTime() - coldStart) / 1_000_000
        assertSame(caller, Thread.currentThread())
        assertEquals(3, atlas.calls.size)
        assertTrue(coldMillis >= 25)
        atlas.calls.clear()
        val warmStart = System.nanoTime()
        handler.invoke(args)
        val warmMillis = (System.nanoTime() - warmStart) / 1_000_000
        assertEquals(1, atlas.calls.size)
        println("MCP attach fixture request thread blocked: cold ${coldMillis}ms for 3 Atlas calls (a revision probe and 2 graph pages); warm ${warmMillis}ms for 1 revision check")
    }

    @Test fun aDeliveringCallbackUsesOnlyCachedRefsOutsideTheStoreMonitor() {
        val directory = Files.createTempDirectory("plan-scope-delivery")
        var resolves = 0
        var cached = 0
        var armed = false
        lateinit var store: WorkPlanStore
        store = WorkPlanStore(directory, resolveRefs = { refs, _ ->
            assertFalse(Thread.holdsLock(store))
            assertFalse(store.deliveringNotification)
            resolves++
            refs.associateWith { listOf("src/known.kt") }
        }, cachedRefs = { refs ->
            assertFalse(Thread.holdsLock(store))
            assertTrue(store.deliveringNotification)
            cached++
            refs.associateWith { emptyList() }
        }, messages = { _, _, _ ->
            if (armed) store.next(thread, secondAgent) { AgentProfile("writer", "Writer", RuntimeKind.Codex) }
        })
        try {
            store.write(thread, PlanPatch(title = "Delivery", ops = listOf(PlanOp.Add("Known", key = "known", refs = listOf("symbol:Known")))), owner)
            store.next(thread, secondAgent) { AgentProfile("writer", "Writer", RuntimeKind.Codex) }
            assertEquals(1, resolves)
            armed = true
            store.write(thread, PlanPatch(message = "Notify"), owner)
            assertEquals(1, resolves)
            assertEquals(1, cached)
            assertEquals(0, store.pendingNotifications)
        } finally { directory.toFile().deleteTreeSafely(within = directory.parent.toFile()) }
    }

    @Test fun normalizedAbsoluteAndRelativeFileRefsConflictInTheStore() = fixture(kernel = false) { workspace, atlas ->
        val absolute = atlas.root.toPath().resolve("src/future.kt").toString()
        workspace.plans.write(thread, PlanPatch(title = "Paths", ops = listOf(
            PlanOp.Add("Held", key = "held", refs = listOf("file:$absolute")),
            PlanOp.Add("Ready", key = "ready", refs = listOf("file:src/future.kt")),
            PlanOp.Status("held", PlanStatus.Doing),
        )), firstAgent)
        assertNull(workspace.plans.next(thread, secondAgent, item = "ready") { workspace.profiles.read("writer") }.candidate)
    }

    @Test fun directoryRefsKeepPrefixConflictsAndLeaveOtherDirectoriesFree() = fixture(kernel = false) { workspace, _ ->
        workspace.plans.write(thread, PlanPatch(title = "Directories", ops = listOf(
            PlanOp.Add("Held directory", key = "held", refs = listOf("file:src/")),
            PlanOp.Add("Descendant", key = "child", refs = listOf("file:src/future.kt")),
            PlanOp.Add("Other", key = "other", refs = listOf("file:other/future.kt")),
            PlanOp.Status("held", PlanStatus.Doing),
        )), firstAgent)
        assertNull(workspace.plans.next(thread, secondAgent, item = "child") { workspace.profiles.read("writer") }.candidate)
        assertNotNull(workspace.plans.next(thread, secondAgent, item = "other") { workspace.profiles.read("writer") }.candidate)
    }

    @Test fun unknownSymbolsAndAnUnreadyAtlasUseWorktrees() = fixture { workspace, atlas ->
        seed(workspace)
        atlas.ready = false
        atlas.missing = true
        for (item in listOf("module", "symbol")) {
            val attached = workspace.attachPlan(thread, listOf(item), "writer", "", owner)
            assertEquals(AgentIsolation.Worktree, attached.run.isolation)
            assertTrue(workspace.submission(attached.run.id).scope.isEmpty())
        }
    }

    @Test fun fileReferencesStayLocalAndShareWithoutAKernel() = fixture(kernel = false) { workspace, _ ->
        seed(workspace)
        val attached = workspace.attachPlan(thread, listOf("free"), "writer", "", owner)
        assertEquals(AgentIsolation.Shared, attached.run.isolation)
        assertEquals(listOf("src/free.kt"), workspace.submission(attached.run.id).scope)
    }

    @Test fun unresolvedModuleRefsStillConflictAndFutureFilesConflictWithoutAtlas() {
        for (ref in listOf("module:unavailable", "file:src/not-created-yet.kt")) fixture(kernel = false) { workspace, _ ->
            workspace.plans.write(thread, PlanPatch(title = "Conflicts", ops = listOf(
                PlanOp.Add("Held", key = "held", refs = listOf(ref)),
                PlanOp.Add("Ready", key = "ready", refs = listOf(ref)),
                PlanOp.Status("held", PlanStatus.Doing),
            )), firstAgent)
            assertNull(workspace.plans.next(thread, secondAgent, item = "ready") { workspace.profiles.read("writer") }.candidate)
        }
    }

    @Test fun ownRefsDoNotFallBackToAParentAndAnyUnresolvedOwnRefUsesAWorktree() {
        for (refs in listOf(listOf("symbol:Shared", "symbol:Missing"), listOf("symbol:Missing"), emptyList())) fixture { workspace, atlas ->
            atlas.missingNames += "Missing"
            workspace.plans.write(thread, PlanPatch(title = "Inheritance", ops = listOf(
                PlanOp.Add("Parent", key = "parent", kind = PlanKind.Component, refs = listOf("module:core")),
                PlanOp.Add("Child", key = "child", parent = "parent", refs = refs),
            )), owner)
            val attached = workspace.attachPlan(thread, listOf("child"), "writer", "", owner)
            val files = workspace.submission(attached.run.id).scope
            assertEquals(if (refs.isEmpty()) AgentIsolation.Shared else AgentIsolation.Worktree, attached.run.isolation)
            assertEquals(if (refs == listOf("symbol:Missing")) emptyList() else listOf("src/shared.kt"), files)
            if (refs.isNotEmpty()) assertTrue(atlas.calls.none { it.startsWith("atlas_graph") })
        }
    }

    @Test fun noRefsUseAWorktreeAndNeverReserveTheCheckout() = fixture { workspace, _ ->
        workspace.plans.write(thread, PlanPatch(title = "Unscoped", ops = listOf(
            PlanOp.Add("Unscoped", key = "unscoped"),
            PlanOp.Add("Scoped", key = "scoped", refs = listOf("file:src/scoped.kt")),
        )), owner)
        val unscoped = workspace.attachPlan(thread, listOf("unscoped"), "writer", "", owner)
        assertEquals(AgentIsolation.Worktree, unscoped.run.isolation)
        assertTrue(workspace.submission(unscoped.run.id).scope.isEmpty())
        assertEquals(AgentIsolation.Shared, workspace.attachPlan(thread, listOf("scoped"), "writer", "", owner).run.isolation)
    }

    @Test fun nextResolvesOnlyDoingLeavesAndCandidatesPassingSeatProfileAndKindChecks() = runBlocking {
        val directory = Files.createTempDirectory("plan-scope-limited")
        val calls = Collections.synchronizedList(ArrayList<String>())
        lateinit var store: WorkPlanStore
        store = WorkPlanStore(directory, resolveRefs = { refs, _ ->
            assertFalse(Thread.holdsLock(store))
            calls += refs
            refs.associateWith { listOf("src/${it.substringAfter(':')}.kt") }
        })
        try {
            store.write(thread, PlanPatch(title = "Limited", ops = listOf(
                PlanOp.Add("Held", key = "held", refs = listOf("symbol:held")),
                PlanOp.Add("Eligible", key = "eligible", kind = PlanKind.Check, refs = listOf("symbol:eligible")),
                PlanOp.Add("Wrong kind", key = "wrong", refs = listOf("symbol:wrong")),
                PlanOp.Add("Blocked", key = "blocked", kind = PlanKind.Check, after = listOf("held"), refs = listOf("symbol:blocked")),
                PlanOp.Add("Decision", key = "decision", kind = PlanKind.Decision, refs = listOf("symbol:decision")),
                PlanOp.Status("held", PlanStatus.Doing),
            )), firstAgent)
            val next = store.next(thread, secondAgent.copy(profile = "checks")) { AgentProfile("checks", "Checks", RuntimeKind.Codex, planKinds = setOf(PlanKind.Check)) }
            assertEquals("eligible", next.candidate?.item?.id)
            assertEquals(setOf("symbol:held", "symbol:eligible"), calls.toSet())
            assertEquals(calls.distinct(), calls)
        } finally { directory.toFile().deleteTreeSafely(within = directory.parent.toFile()) }
    }

    @Test fun resolutionRetriesStopAtThreeRoundsAndKeepNewRefsRaw() {
        val directory = Files.createTempDirectory("plan-scope-retries")
        var rounds = 0
        val calls = Collections.synchronizedList(ArrayList<String>())
        lateinit var store: WorkPlanStore
        store = WorkPlanStore(directory, resolveRefs = { refs, _ ->
            check(++rounds <= 3) { "Resolution exceeded three rounds" }
            assertFalse(Thread.holdsLock(store))
            calls += refs
            store.write(thread, PlanPatch(ops = listOf(PlanOp.Add("New $rounds", key = "new$rounds", refs = listOf("symbol:new$rounds")))), owner)
            refs.associateWith { listOf("src/${it.substringAfter(':')}.kt") }
        })
        try {
            store.write(thread, PlanPatch(title = "Retries", ops = listOf(PlanOp.Add("First", key = "first", refs = listOf("symbol:first")))), owner)
            assertNotNull(store.next(thread, owner) { AgentProfile("writer", "Writer", RuntimeKind.Echo) }.candidate)
            assertEquals(3, rounds)
            assertEquals(calls.distinct(), calls)
        } finally { directory.toFile().deleteTreeSafely(within = directory.parent.toFile()) }
    }

    @Test fun aConcurrentPlanWriterCanFinishWhileScopeResolvesAndNewRefsAreReadOnce() = runBlocking {
        val directory = Files.createTempDirectory("plan-scope-lock")
        lateinit var store: WorkPlanStore
        val calls = Collections.synchronizedList(ArrayList<String>())
        store = WorkPlanStore(directory, resolveRefs = { refs, _ ->
            assertFalse(Thread.holdsLock(store))
            refs.forEach { calls += it }
            withContext(Dispatchers.Default) {
                if (store.plan(thread)?.item("second") == null) store.write(thread,
                    PlanPatch(ops = listOf(PlanOp.Add("Second", key = "second", refs = listOf("symbol:Second")))), owner)
            }
            refs.associateWith { listOf(if (it == "symbol:Second") "src/second.kt" else "src/first.kt") }
        })
        try {
            store.write(thread, PlanPatch(title = "Concurrent scope", ops = listOf(PlanOp.Add("First", key = "first", refs = listOf("module:first")))), owner)
            withTimeout(5_000) { withContext(Dispatchers.Default) { store.next(thread, owner) { AgentProfile("writer", "Writer", RuntimeKind.Echo) } } }
            assertEquals(listOf("module:first", "symbol:Second"), calls)
            assertNotNull(store.plan(thread)?.item("second"))
            Unit
        } finally { directory.toFile().deleteTreeSafely(within = directory.toFile().parentFile) }
    }
}
