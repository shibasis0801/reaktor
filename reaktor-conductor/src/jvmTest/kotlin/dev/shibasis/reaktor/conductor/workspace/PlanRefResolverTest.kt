package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.test.*

class PlanRefResolverTest {
    @Test fun cachedLookupsNeverRunModuleFolderIO() {
        val root = Files.createTempDirectory("ref-cached-folders").toFile()
        val calls = AtomicInteger()
        try {
            PlanRefResolver(root, null, modules = { calls.incrementAndGet(); emptyList() }).use { resolver ->
                assertTrue(resolver.cached(listOf("module:engine")).getValue("module:engine").isEmpty())
                assertEquals(0, calls.get(), "A notification callback may only read the resolver cache")
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun moduleFolderIOIsInsideTheCallerDeadlineAndContinuesAfterIt() {
        val root = Files.createTempDirectory("ref-bounded-folders").toFile()
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val resolver = PlanRefResolver(root, null, modules = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            listOf("${root.name}/modules/engine")
        })
        try {
            val request = executor.submit<Map<String, List<String>>> {
                runBlocking { resolver.resolve(listOf("module:engine"), System.nanoTime() + 200_000_000L) }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(request.get(1, TimeUnit.SECONDS).getValue("module:engine").isEmpty())
            release.countDown()
            val answer = runBlocking { resolver.resolve(listOf("module:engine")) }
            assertEquals(listOf("modules/engine"), answer["module:engine"])
        } finally {
            release.countDown()
            resolver.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            root.deleteTreeSafely(within = root.parentFile)
        }
    }

    @Test fun aCancelledGraphDoesNotDiscardACompletedSymbol() = runBlocking {
        val root = Files.createTempDirectory("ref-cancelled-load").toFile()
        val symbol = CompletableDeferred<Unit>()
        try {
            PlanRefResolver(root, { name, _ ->
                if (name == "atlas_graph") {
                    symbol.await()
                    throw kotlinx.coroutines.CancellationException("Atlas restarted")
                }
                symbol.complete(Unit)
                buildJsonObject { put("path", "src/Available.kt") }
            }).use { resolver ->
                val answer = resolver.resolve(listOf("module:Restarted", "symbol:Available"))
                assertEquals(listOf("src/Available.kt"), answer["symbol:Available"])
                assertTrue(answer.getValue("module:Restarted").isEmpty())
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun aConfirmedGraphRevisionIsReusedForFiveSeconds() = runBlocking {
        val root = Files.createTempDirectory("ref-confirmed-revision").toFile()
        var now = 1_000L
        val calls = ArrayList<Int>()
        try {
            PlanRefResolver(root, { _, args ->
                calls += args.getValue("chars").jsonPrimitive.int
                buildJsonObject {
                    put("ready", true); put("revision", "confirmed"); put("pages", 1)
                    putJsonObject("shape") { putJsonArray("nodes") {
                        add(buildJsonObject { putJsonObject("attributes") { put("module", "engine"); put("path", "src/Engine.kt") } })
                    } }
                }
            }, clock = { now }).use { resolver ->
                val expected = listOf("src/Engine.kt")
                assertEquals(expected, resolver.resolve(listOf("module:engine"))["module:engine"])
                calls.clear()
                now += 4_999
                assertEquals(expected, resolver.resolve(listOf("module:engine"))["module:engine"])
                assertTrue(calls.isEmpty(), "The confirmed revision must avoid another Atlas probe")
                now++
                assertEquals(expected, resolver.resolve(listOf("module:engine"))["module:engine"])
                assertEquals(listOf(20_000), calls)
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun aFastSymbolIsReturnedWhileTheGraphIsStillBlocked() = runBlocking {
        val root = Files.createTempDirectory("ref-concurrent-graph").toFile()
        val release = CompletableDeferred<Unit>()
        val symbolStarted = CompletableDeferred<Unit>()
        PlanRefResolver(root, { name, _ ->
            if (name == "atlas_graph") { release.await(); buildJsonObject {} }
            else { symbolStarted.complete(Unit); buildJsonObject { put("path", "src/Fast.kt") } }
        }).use { resolver ->
            try {
                val answer = resolver.resolve(listOf("module:Slow", "symbol:Fast"))
                assertTrue(symbolStarted.isCompleted)
                assertEquals(listOf("src/Fast.kt"), answer["symbol:Fast"])
                assertTrue(answer.getValue("module:Slow").isEmpty())
            } finally { release.complete(Unit); root.deleteTreeSafely(within = root.parentFile) }
        }
    }

    @Test fun twoSlowSymbolsStartTogetherAndBothResolveWithinOneWait() = runBlocking {
        val root = Files.createTempDirectory("ref-concurrent-symbols").toFile()
        PlanRefResolver(root, { _, args ->
            delay(2_000)
            buildJsonObject { put("path", "src/${args.getValue("name").jsonPrimitive.content}.kt") }
        }).use { resolver ->
            try {
                val start = System.nanoTime()
                val answer = resolver.resolve(listOf("symbol:First", "symbol:Second"))
                assertEquals(listOf("src/First.kt"), answer["symbol:First"])
                assertEquals(listOf("src/Second.kt"), answer["symbol:Second"])
                val elapsed = (System.nanoTime() - start) / 1_000_000
                assertTrue(elapsed < 2_800, "Concurrent symbol wait took ${elapsed}ms")
                val loads = PlanRefResolver::class.java.getDeclaredField("symbolLoads").apply { isAccessible = true }.get(resolver) as Map<*, *>
                assertEquals(0, loads.size)
                println("Concurrent symbols: ${elapsed}ms")
            } finally { root.deleteTreeSafely(within = root.parentFile) }
        }
    }

    @Test fun moduleFoldersResolveWithoutCallingAtlas() = runBlocking {
        val root = Files.createTempDirectory("ref-module-folder").toFile()
        val calls = AtomicInteger()
        try {
            val resolver = PlanRefResolver(root, { _, _ ->
                calls.incrementAndGet()
                error("A module folder does not need Atlas")
            }, modules = { names ->
                assertEquals(listOf("engine"), names)
                listOf("${root.name}/modules/engine")
            })
            val answer = resolver.resolve(listOf("module:engine"))
            assertEquals(0, calls.get())
            assertEquals(listOf("modules/engine"), answer["module:engine"])
            assertEquals(answer, resolver.cached(listOf("module:engine")))
            assertTrue(AgentSourceScope.conflicts(listOf("file:modules/engine"), listOf("file:modules/engine/src/Engine.kt")))
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun aFailedGraphPageResumesWithoutRepeatingEarlierPages() = runBlocking {
        val root = Files.createTempDirectory("ref-page-resume").toFile()
        val pages = mutableListOf<Int>()
        var failed = false
        try {
            val resolver = PlanRefResolver(root, { _, args ->
                val index = args.getValue("page").jsonPrimitive.int
                if (args.getValue("chars").jsonPrimitive.int == 250_000) {
                    pages += index
                    if (index == 1 && !failed) { failed = true; error("One page is unavailable") }
                }
                buildJsonObject {
                    put("ready", true); put("revision", "resume"); put("pages", 3)
                    putJsonObject("shape") { putJsonArray("nodes") {
                        add(buildJsonObject { putJsonObject("attributes") { put("module", "engine"); put("path", "src/Page$index.kt") } })
                    } }
                }
            })
            assertTrue(resolver.resolve(listOf("module:engine")).getValue("module:engine").isEmpty())
            assertEquals(listOf("src/Page0.kt", "src/Page1.kt", "src/Page2.kt"), resolver.resolve(listOf("module:engine"))["module:engine"])
            assertEquals(listOf(0, 1, 1, 2), pages)
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun aSlowSymbolReadReturnsUnresolvedWithinTheCallerBound() = boundedRead("symbol:Slow")

    @Test fun aSlowRevisionProbeReturnsUnresolvedWithinTheCallerBound() = boundedRead("module:Slow")

    private fun boundedRead(ref: String) {
        val root = Files.createTempDirectory("ref-bounded-read").toFile()
        val executor = Executors.newSingleThreadExecutor()
        val release = CompletableDeferred<Unit>()
        val resolver = PlanRefResolver(root, { _, _ ->
            release.await()
            buildJsonObject { put("path", "src/Slow.kt") }
        })
        val request = executor.submit<Map<String, List<String>>> { runBlocking { resolver.resolve(listOf(ref)) } }
        try {
            assertTrue(request.get(4_500, TimeUnit.MILLISECONDS).getValue(ref).isEmpty())
        } finally {
            release.complete(Unit)
            request.cancel(true)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            root.deleteTreeSafely(within = root.parentFile)
        }
    }

    @Test fun rejectedAtlasPathsResolveToNothing() = runBlocking {
        val root = Files.createTempDirectory("ref-invalid-path").toFile()
        try {
            val resolver = PlanRefResolver(root, { _, _ -> buildJsonObject { put("path", "a\u0000b") } })
            assertTrue(resolver.resolve(listOf("symbol:InvalidPath")).getValue("symbol:InvalidPath").isEmpty())
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun aWarmModuleLookupReusesItsConfirmedRevisionThenProbesWithASmallPage() = runBlocking {
        val root = Files.createTempDirectory("ref-small-probe").toFile()
        val sizes = mutableListOf<Int>()
        var now = 1_000L
        try {
            val resolver = PlanRefResolver(root, { _, args ->
                sizes += args.getValue("chars").jsonPrimitive.int
                buildJsonObject {
                    put("ready", true); put("revision", "small-probe"); put("pages", 1)
                    putJsonObject("shape") { putJsonArray("nodes") {
                        add(buildJsonObject { putJsonObject("attributes") { put("module", "engine"); put("path", "src/Engine.kt") } })
                    } }
                }
            }, clock = { now })
            assertEquals(listOf("src/Engine.kt"), resolver.resolve(listOf("module:engine")).getValue("module:engine"))
            sizes.clear()
            resolver.resolve(listOf("module:engine"))
            assertTrue(sizes.isEmpty())
            now += 5_000
            resolver.resolve(listOf("module:engine"))
            assertEquals(listOf(20_000), sizes)
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun concurrentModuleLookupsShareOneGraphLoadForTheRevision() = runBlocking {
        val root = Files.createTempDirectory("ref-concurrent-graph").toFile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val firstPages = AtomicInteger()
        val laterPages = AtomicInteger()
        try {
            val resolver = PlanRefResolver(root, { _, args ->
                val full = args.getValue("chars").jsonPrimitive.int == 250_000
                val page = args.getValue("page").jsonPrimitive.int
                if (full && page == 0) {
                    firstPages.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                } else if (full) laterPages.incrementAndGet()
                buildJsonObject {
                    put("ready", true); put("revision", "concurrent"); put("pages", 2)
                    putJsonObject("shape") { putJsonArray("nodes") {
                        if (page == 1) add(buildJsonObject {
                            putJsonObject("attributes") { put("module", "engine"); put("path", "src/Engine.kt") }
                        })
                    } }
                }
            })
            val requests = (1..8).map { async(Dispatchers.Default) { resolver.resolve(listOf("module:engine")) } }
            try {
                withTimeout(5_000) { entered.await() }
                delay(150)
                assertEquals(1, firstPages.get())
            } finally { release.complete(Unit) }
            withTimeout(5_000) { requests.awaitAll() }.forEach { assertEquals(listOf("src/Engine.kt"), it.getValue("module:engine")) }
            assertEquals(1, firstPages.get())
            assertEquals(1, laterPages.get())
        } finally {
            release.complete(Unit)
            root.deleteTreeSafely(within = root.parentFile)
        }
    }

    @Test fun moduleCacheKeepsModulePathsWithoutRetainingJsonNodes() = runBlocking {
        val root = Files.createTempDirectory("ref-retained-paths").toFile()
        try {
            val resolver = PlanRefResolver(root, { _, _ -> buildJsonObject {
                put("ready", true); put("revision", "paths"); put("pages", 1)
                putJsonObject("shape") { putJsonArray("nodes") {
                    add(buildJsonObject {
                        put("label", "Source declaration")
                        putJsonObject("attributes") { put("module", "engine"); put("path", "src/Engine.kt"); put("declaration", "irrelevant source") }
                    })
                } }
            } })
            resolver.resolve(listOf("module:engine"))
            val graph = PlanRefResolver::class.java.getDeclaredField("graph").apply { isAccessible = true }.get(resolver)
            assertNotNull(graph)
            val values = graph.javaClass.declaredFields.filterNot { it.isSynthetic }.map { it.isAccessible = true; it.get(graph) }
            assertFalse(values.any { value -> value is JsonElement || (value as? Collection<*>)?.any { it is JsonElement } == true })
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun anEmptySymbolAnswerIsCachedUntilItsLifetimeEndsAndFailuresAreNot() = runBlocking {
        val root = Files.createTempDirectory("ref-empty-symbol").toFile()
        var now = 1_000L
        var calls = 0
        var fail = false
        try {
            val resolver = PlanRefResolver(root, { _, _ ->
                calls++
                if (fail) error("Atlas is unavailable")
                buildJsonObject { putJsonArray("also") {} }
            }, clock = { now }, symbolLifetimeMillis = 100)
            repeat(2) { assertTrue(resolver.resolve(listOf("symbol:Unknown")).getValue("symbol:Unknown").isEmpty()) }
            assertEquals(1, calls)
            now += 100
            resolver.resolve(listOf("symbol:Unknown"))
            assertEquals(2, calls)
            fail = true
            repeat(2) { resolver.resolve(listOf("symbol:Offline")) }
            assertEquals(4, calls)
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun modulesUseGraphRevisionsAndSymbolsExpireOnTheInjectedClock() = runBlocking {
        val root = Files.createTempDirectory("plan-ref-cache").toFile()
        var now = 1_000L
        var revision = "one"
        var ready = true
        var missing = false
        val calls = Collections.synchronizedList(ArrayList<String>())
        val resolver = PlanRefResolver(root, { name, args ->
            calls += "$name:${args["page"] ?: args["name"]}:${args["chars"]}"
            if (name == "atlas_read") {
                if (missing) error("Unknown symbol")
                buildJsonObject { put("path", "src/$revision.kt") }
            } else buildJsonObject {
                put("ready", ready); put("revision", revision); put("pages", 2)
                putJsonObject("shape") { putJsonArray("nodes") {
                    if (args.getValue("page").jsonPrimitive.int == 1) add(buildJsonObject {
                        putJsonObject("attributes") { put("module", "core"); put("path", "src/$revision.kt") }
                    })
                } }
            }
        }, clock = { now }, symbolLifetimeMillis = 100)
        try {
            val cold = resolver.resolve(listOf("module:core", "symbol:Known", "module:core", "symbol:Known"))
            assertEquals(4, calls.size)
            assertEquals(calls.distinct(), calls)
            assertEquals(listOf("src/one.kt"), cold["module:core"])
            calls.clear()
            assertEquals(cold, resolver.resolve(listOf("module:core", "symbol:Known")))
            assertTrue(calls.isEmpty())
            calls.clear()
            now += 5_000
            revision = "two"
            val changed = resolver.resolve(listOf("module:core", "symbol:Known"))
            assertEquals(listOf("src/two.kt"), changed["module:core"])
            assertEquals(listOf("src/two.kt"), changed["symbol:Known"])
            assertEquals(4, calls.size)
            now += 100
            calls.clear()
            assertTrue(resolver.cached(listOf("symbol:Known")).getValue("symbol:Known").isEmpty())
            assertTrue(calls.isEmpty())
            assertEquals(listOf("src/two.kt"), resolver.resolve(listOf("symbol:Known"))["symbol:Known"])
            assertEquals(1, calls.size)
            missing = true
            repeat(2) { assertTrue(resolver.resolve(listOf("symbol:Missing")).getValue("symbol:Missing").isEmpty()) }
            assertEquals(3, calls.size)
            missing = false
            assertEquals(listOf("src/two.kt"), resolver.resolve(listOf("symbol:Missing"))["symbol:Missing"])
            ready = false
            now += 5_000
            revision = "unready"
            assertTrue(resolver.resolve(listOf("module:core")).getValue("module:core").isEmpty())
            assertTrue(resolver.cached(listOf("module:core")).getValue("module:core").isEmpty())
            assertTrue(resolver.resolve(listOf("module:later")).getValue("module:later").isEmpty())
            ready = true
            revision = "three"
            assertEquals(listOf("src/three.kt"), resolver.resolve(listOf("module:core"))["module:core"])
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun localFilesNormalizeWithoutAtlasAndIncludeFilesNotCreatedYet() = runBlocking {
        val root = Files.createTempDirectory("plan-ref-local").toFile()
        try {
            val resolver = PlanRefResolver(root, { _, _ -> error("Files must not reach Atlas") })
            val refs = listOf("file:src/../src/future.kt", "file:${root.path}/src/future.kt", "file:src/")
            val answer = resolver.resolve(refs)
            assertEquals(listOf("src/future.kt"), answer[refs[0]])
            assertEquals(answer[refs[0]], answer[refs[1]])
            assertEquals(listOf("src"), answer[refs[2]])
            assertEquals(answer, resolver.cached(refs))
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }
}
