package dev.shibasis.reaktor.blueprint.capture

import dev.shibasis.reaktor.blueprint.editor.*
import dev.shibasis.reaktor.blueprint.editor.GraphChange
import dev.shibasis.reaktor.graph.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class CaptureConversionTest {
    private val author = CaptureAuthor("(codex, xhigh, designer)", "bpo-designer", 1)
    private val sketch = Capture.Note("sketch", "Measure telemetry coverage", 240.0, 100.0, CaptureAnchor.World(Spot(20.0, 30.0)), author)
    private val source = GraphShape(listOf(ScopeShape("launch", "Launch")), listOf(
        NodeShape("source", "launch", "Telemetry", "Service", NodeKind.Service, ports = listOf(PortShape("out", "Signal", true, PortWiring.Unused))),
        NodeShape("sink", "launch", "Coverage", "Service", NodeKind.Service, ports = listOf(PortShape("in", "Signal", false, PortWiring.Unmatched))),
    ), emptyList(), emptyList())
    private val node = NodeShape("coverage-task", "launch", "Measure telemetry coverage", "Task", NodeKind.Node, attributes = mapOf("status" to "Todo"))
    private fun editor() = GraphEditor(source, Placement(capture = listOf(sketch))).also {
        it.editRevision = EditRevision("source:1", "presentation:1")
        it.permits = { _, _ -> true }
        it.validate = { Verdict(true, "") }
        it.commit = { _, _ -> EditReceipt.Accepted(EditRevision("source:2", "presentation:2")) }
    }

    @Test fun taskPreviewNamesTheExactWriteAndPersistsItsReceiptInTheSameCommit(): Unit = runBlocking {
        val editor = editor()
        var saved: GraphDraft? = null
        editor.commit = { draft, expected ->
            assertEquals(EditRevision("source:1", "presentation:1"), expected)
            saved = draft
            EditReceipt.Accepted(EditRevision("source:2", "presentation:2"))
        }
        val preview = CaptureConversion.task(editor, sketch.id, node, "plan:hangar:launch", author)
        assertEquals(CaptureConversionKind.Task, preview.kind)
        assertEquals(node.label, preview.title)
        assertEquals(node.id, preview.identity)
        assertEquals("plan:hangar:launch", preview.writeTarget)
        assertEquals(source, editor.shape)
        assertEquals(listOf(sketch), editor.placement.capture)
        assertIs<EditReceipt.Accepted>(preview.confirm(editor))
        assertEquals(node, saved?.shape?.node(node.id))
        val restored = Json.decodeFromString<Placement>(Json.encodeToString(saved!!.placement))
        assertEquals(sketch, restored.capture.first())
        val receipt = assertNotNull(CaptureConversion.receipt(restored, sketch.id))
        assertEquals(author, receipt.author)
        assertTrue(node.id in receipt.text && node.label in receipt.text && preview.writeTarget in receipt.text)
        assertEquals(sketch.id, receipt.replyTo)
        assertEquals(editor.placement, restored)
    }

    @Test fun nodeAndBothLinkKindsPreviewRealEndsAndDirection(): Unit = runBlocking {
        val editor = editor()
        assertEquals(CaptureConversionKind.Node, CaptureConversion.node(editor, sketch.id, node, "graph:launch").kind)
        val wire = WireShape("sink", "in", "source", "out", "Signal")
        val wired = CaptureConversion.link(editor, sketch.id, wire, "graph:launch")
        assertEquals("Telemetry · out", wired.from)
        assertEquals("Coverage · in", wired.to)
        assertEquals(wire.id, wired.identity)
        assertIs<EditReceipt.Accepted>(wired.confirm(editor))
        assertEquals(listOf(wire), editor.shape.wires)
        val routeEditor = editor()
        val route = RouteShape("source", "sink", "inspect")
        val routed = CaptureConversion.link(routeEditor, sketch.id, route, "graph:launch")
        assertEquals("Telemetry", routed.from)
        assertEquals("Coverage", routed.to)
        assertIs<EditReceipt.Accepted>(routed.confirm(routeEditor))
        assertEquals(listOf(route), routeEditor.shape.routes)
    }

    @Test fun domainRefusalAndReadOnlyAuthorityKeepTheSketchAndHistory(): Unit = runBlocking {
        for (readOnly in listOf(false, true)) {
            val editor = editor()
            var commits = 0
            editor.commit = { _, _ -> commits++; error("Must not commit") }
            if (readOnly) editor.permits = { _, _ -> false } else editor.validate = { Verdict(false, "This workspace cannot create tasks") }
            val before = editor.placement
            val history = editor.history.toList()
            val result = CaptureConversion.task(editor, sketch.id, node, "plan:launch").confirm(editor)
            assertIs<EditReceipt.Refused>(result)
            assertTrue(result.message.isNotBlank())
            assertEquals(before, editor.placement)
            assertEquals(source, editor.shape)
            assertEquals(history, editor.history)
            assertEquals(0, commits)
        }
    }

    @Test fun stalePreviewAndHostConflictLeaveNoReceipt(): Unit = runBlocking {
        val editor = editor()
        val preview = CaptureConversion.task(editor, sketch.id, node, "plan:launch")
        editor.editRevision = EditRevision("source:next", "presentation:1")
        assertTrue((preview.confirm(editor) as EditReceipt.Refused).stale)
        val fresh = CaptureConversion.task(editor, sketch.id, node, "plan:launch")
        editor.commit = { _, _ -> EditReceipt.Refused("The plan revision changed", stale = true) }
        val refused = fresh.confirm(editor) as EditReceipt.Refused
        assertTrue(refused.stale)
        assertEquals("The plan revision changed", refused.message)
        assertEquals(listOf(sketch), editor.placement.capture)
        assertEquals(source, editor.shape)
    }

    @Test fun cancelNeverCallsTheHostAndAnAcceptedPreviewCannotCommitTwice(): Unit = runBlocking {
        val editor = editor()
        var commits = 0
        editor.commit = { _, _ -> commits++; EditReceipt.Accepted(EditRevision("2", "2")) }
        val cancelled = CaptureConversion.task(editor, sketch.id, node, "plan:launch")
        assertIs<EditReceipt.Refused>(cancelled.cancel())
        assertIs<EditReceipt.Refused>(cancelled.confirm(editor))
        assertEquals(0, commits)
        val accepted = CaptureConversion.task(editor, sketch.id, node, "plan:launch")
        assertIs<EditReceipt.Accepted>(accepted.confirm(editor))
        assertIs<EditReceipt.Accepted>(accepted.confirm(editor))
        assertEquals(1, commits)
        assertFailsWith<IllegalArgumentException> { CaptureConversion.task(editor, sketch.id, node, "plan:launch") }
    }

    @Test fun coroutineCancellationKeepsTheSketchAndCanBeRetried(): Unit = runBlocking {
        val editor = editor()
        editor.commit = { _, _ -> throw CancellationException("Cancelled by the host") }
        val preview = CaptureConversion.task(editor, sketch.id, node, "plan:launch")
        try { preview.confirm(editor); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(listOf(sketch), editor.placement.capture)
        assertEquals(source, editor.shape)
        assertNull(editor.pending)
        editor.commit = { _, _ -> EditReceipt.Accepted(EditRevision("2", "2")) }
        assertIs<EditReceipt.Accepted>(preview.confirm(editor))
    }

    @Test fun conflictingIdentityOrMissingCaptureCannotBeSilentlyConverted(): Unit = runBlocking {
        val editor = editor()
        assertFailsWith<IllegalArgumentException> { CaptureConversion.task(editor, "missing", node, "plan:launch") }
        assertFailsWith<IllegalArgumentException> { CaptureConversion.node(editor, sketch.id, node, " ") }
        val duplicate = node.copy(id = "source")
        assertFailsWith<IllegalArgumentException> { CaptureConversion.task(editor, sketch.id, duplicate, "plan:launch") }
        assertEquals(listOf(sketch), editor.placement.capture)
        assertEquals(source, editor.shape)
    }
    private fun batchSetup(shape: GraphShape = source): Triple<GraphEditor, List<CaptureConversionRequest>, List<GraphChange>> {
        val first = node.copy(id = "prepare-contract", label = "Prepare contract", ports = listOf(PortShape("done", "Dependency", true, PortWiring.Unused)))
        val second = node.copy(id = "verify-contract", label = "Verify contract", ports = listOf(PortShape("needs", "Dependency", false, PortWiring.Unmatched)))
        val wire = WireShape(second.id, "needs", first.id, "done", "Dependency")
        val captures = listOf("first-sketch", "second-sketch", "link-sketch").map { sketch.copy(id = it) }
        val editor = GraphEditor(shape, Placement(capture = captures)).also {
            it.editRevision = EditRevision("source:1", "presentation:1")
            it.permits = { _, _ -> true }
            it.validate = { Verdict(true, "") }
            it.commit = { _, _ -> EditReceipt.Accepted(EditRevision("source:2", "presentation:2")) }
        }
        val requests = listOf(
            CaptureConversionRequest(captures[0].id, CaptureConversionKind.Task, first.id, "plan:launch", author),
            CaptureConversionRequest(captures[1].id, CaptureConversionKind.Task, second.id, "plan:launch", author),
            CaptureConversionRequest(captures[2].id, CaptureConversionKind.Link, wire.id, "plan:launch", author),
        )
        val changes = listOf(GraphChange("addNode", "Prepare contract", node = first),
            GraphChange("addNode", "Verify contract", node = second), GraphChange("connect", "Wait for preparation", wire = wire))
        return Triple(editor, requests, changes)
    }

    @Test fun batchWritesTwoTasksAndTypedWireInOneAwaitedGraphProposal(): Unit = runBlocking {
        val (editor, requests, changes) = batchSetup()
        val answer = CompletableDeferred<EditReceipt>()
        var writes = 0
        var draft: GraphDraft? = null
        editor.commit = { next, expected ->
            writes++
            assertEquals(EditRevision("source:1", "presentation:1"), expected)
            draft = next
            answer.await()
        }
        val preview = CaptureConversion.batch(editor, requests, changes)
        assertEquals(requests.map { it.identity }, preview.entries.map { it.identity })
        assertEquals("Prepare contract · done", preview.entries.last().from)
        assertEquals("Verify contract · needs", preview.entries.last().to)
        val result = async(start = CoroutineStart.UNDISPATCHED) { preview.confirm(editor) }
        assertFalse(result.isCompleted)
        assertEquals(1, writes)
        assertEquals(source, editor.shape)
        assertTrue(editor.placement.captures.none { it.id.startsWith("conversion:") })
        assertEquals(changes, editor.pending!!.part.changes)
        assertEquals(requests.map { it.identity }.toSet(), draft!!.shape.nodes.filter { it.id !in source.nodes.map { n -> n.id } }.map { it.id }.toSet() + draft!!.shape.wires.map { it.id })
        answer.complete(EditReceipt.Accepted(EditRevision("source:2", "presentation:2")))
        assertIs<EditReceipt.Accepted>(result.await())
        assertEquals(1, editor.history.size)
        for (request in requests) {
            val receipt = assertNotNull(CaptureConversion.receipt(editor.placement, request.capture))
            assertEquals(author, receipt.author)
            assertTrue(request.identity in receipt.text)
        }
        assertIs<EditReceipt.Accepted>(preview.confirm(editor))
        assertEquals(1, writes)
    }

    @Test fun batchHostRefusalOrUncertainPartialWriteKeepsAllSketchesAndNoLocalReceipts(): Unit = runBlocking {
        for (uncertain in listOf(false, true)) {
            val (editor, requests, changes) = batchSetup()
            val placement = editor.placement
            val history = editor.history.toList()
            val persisted = mutableSetOf<String>()
            editor.commit = { _, _ ->
                persisted += requests.first().identity
                if (uncertain) throw IllegalStateException("The write outcome is uncertain")
                EditReceipt.Refused("One operation persisted; remaining operations were refused")
            }
            assertIs<EditReceipt.Refused>(CaptureConversion.batch(editor, requests, changes).confirm(editor))
            assertEquals(setOf(requests.first().identity), persisted)
            assertEquals(source, editor.shape)
            assertEquals(placement, editor.placement)
            assertEquals(history, editor.history)
            assertTrue(requests.all { CaptureConversion.receipt(editor.placement, it.capture) == null })
        }
    }

    @Test fun batchReconciliationKeepsReservedIdsAndOnlyWritesRemainingChanges(): Unit = runBlocking {
        val (_, requests, changes) = batchSetup()
        val partial = source.applying(changes.take(1))
        val (editor, _, _) = batchSetup(partial)
        var submitted: List<GraphChange>? = null
        editor.validate = { submitted = it.changes; Verdict(true, "") }
        val preview = CaptureConversion.batch(editor, requests, changes.drop(1))
        assertEquals(requests.map { it.identity }, preview.entries.map { it.identity })
        assertIs<EditReceipt.Accepted>(preview.confirm(editor))
        assertEquals(changes.drop(1), submitted)
        assertEquals(1, editor.shape.nodes.count { it.id == requests.first().identity })
        assertTrue(editor.shape.nodes.none { it.id.endsWith("-2") })
        assertEquals(3, requests.count { CaptureConversion.receipt(editor.placement, it.capture) != null })
        val (complete, _, _) = batchSetup(source.applying(changes))
        var placementWrites = 0
        complete.commit = { _, _ -> error("No repeated topology write") }
        complete.savePlacement = { _, expected ->
            placementWrites++
            assertEquals(EditRevision("source:1", "presentation:1"), expected)
            assertTrue(requests.filter { it.kind != CaptureConversionKind.Link }.all { complete.shape.node(it.identity) != null })
            assertTrue(complete.shape.wires.any { it.id == requests.last().identity })
            EditReceipt.Accepted(EditRevision("source:1", "presentation:2"))
        }
        assertIs<EditReceipt.Accepted>(CaptureConversion.batch(complete, requests, emptyList()).confirm(complete))
        assertEquals(1, placementWrites)
    }

    @Test fun batchStalenessAuthorityAndCancellationNeverPublishConversionReceipts(): Unit = runBlocking {
        for (reason in listOf("stale", "authority", "domain", "cancel")) {
            val (editor, requests, changes) = batchSetup()
            var writes = 0
            editor.commit = { _, _ -> writes++; error("No write is allowed") }
            val before = editor.placement
            val preview = CaptureConversion.batch(editor, requests, changes)
            when (reason) {
                "stale" -> editor.editRevision = EditRevision("source:next", "presentation:1")
                "authority" -> editor.permits = { _, _ -> false }
                "domain" -> editor.validate = { Verdict(false, "This plan cannot create tasks") }
                "cancel" -> assertIs<EditReceipt.Refused>(preview.cancel())
            }
            assertIs<EditReceipt.Refused>(preview.confirm(editor))
            assertEquals(0, writes)
            assertEquals(source, editor.shape)
            assertEquals(before, editor.placement)
        }
    }

    @Test fun batchRejectsDuplicateCapturesWrongTargetsAndMissingDirectionalPorts() {
        val (editor, requests, changes) = batchSetup()
        assertFailsWith<IllegalArgumentException> { CaptureConversion.batch(editor, requests + requests.first(), changes) }
        assertFailsWith<IllegalArgumentException> { CaptureConversion.batch(editor, requests.mapIndexed { i, request -> if (i == 0) request.copy(writeTarget = "plan:other") else request }, changes) }
        assertFailsWith<IllegalArgumentException> { CaptureConversion.batch(editor, requests.mapIndexed { i, request -> if (i == 0) request.copy(capture = "absent") else request }, changes) }
        val wrongPorts = changes.mapIndexed { i, change -> if (i == 0) change.node!!.let { node -> change.copy(node = node.copy(ports = node.ports.map { it.copy(provides = false) })) } else change }
        assertFailsWith<IllegalArgumentException> { CaptureConversion.batch(editor, requests, wrongPorts) }
        val unrelated = changes + GraphChange("removeNode", "Remove an unrelated node", before = source.nodes.first())
        assertFailsWith<IllegalArgumentException> { CaptureConversion.batch(editor, requests, unrelated) }
        assertEquals(source, editor.shape)
        assertTrue(editor.placement.captures.none { it.id.startsWith("conversion:") })
    }

    @Test fun batchCanonicalEnvelopeRequiresCompleteBeforeAndAfterSnapshots(): Unit = runBlocking {
        for (reason in listOf("before", "after", "conflict", "wire type")) {
            val (editor, requests, changes) = batchSetup()
            val before = editor.shape
            val placement = editor.placement
            val complete = before.replay(GraphPart(changes = changes))
            assertTrue(complete.conflicts.isEmpty())
            assertEquals(2, complete.shape.nodes.size - before.nodes.size)
            assertEquals(1, complete.shape.wires.size)
            var commits = 0
            var saves = 0
            editor.commit = { _, _ -> commits++; error("An unmatched envelope cannot commit") }
            editor.savePlacement = { _, _ -> saves++; error("An unmatched envelope cannot save") }
            val proposed = when (reason) {
                "conflict" -> changes + GraphChange("connect", "Missing directional endpoint", wire = changes.last().wire!!.copy(consumerPort = "missing"))
                "wire type" -> changes.dropLast(1) + changes.last().copy(wire = changes.last().wire!!.copy(type = "Different"))
                else -> changes
            }
            val targetRequests = if (reason == "wire type") requests.dropLast(1) + requests.last().copy(identity = proposed.last().wire!!.id) else requests
            val expectedBefore = if (reason == "before") before.copy(definitionDigest = "different-source") else before
            val expectedAfter = if (reason == "after") complete.shape.copy(nodes = complete.shape.nodes.mapIndexed { index, node ->
                if (index == 0) node.copy(attributes = node.attributes + ("independent" to "not in replay")) else node
            }) else if (reason == "wire type") before.replay(GraphPart(changes = proposed)).also { assertTrue(it.conflicts.isEmpty()) }.shape else complete.shape
            if (reason == "conflict") assertTrue(before.replay(GraphPart(changes = proposed)).conflicts.isNotEmpty())
            assertFailsWith<IllegalArgumentException>(reason) {
                CaptureConversion.batch(editor, targetRequests, proposed, expectedBefore, expectedAfter)
            }
            assertEquals(before, editor.shape)
            assertEquals(placement, editor.placement)
            assertTrue(editor.history.isEmpty())
            assertNull(editor.pending)
            assertEquals(0, commits)
            assertEquals(0, saves)
            assertTrue(requests.all { CaptureConversion.receipt(editor.placement, it.capture) == null })
        }
    }

    @Test fun batchCanonicalEnvelopeCannotAuthorizeUnrelatedStructuralChanges() {
        val existingWire = WireShape("sink", "in", "source", "out", "Signal")
        val shape = source.copy(scopes = source.scopes + ScopeShape("other", "Other"), wires = listOf(existingWire))
        val original = shape.nodes.first()
        val mutations = listOf(
            "label" to GraphChange("changeNode", "Rename existing node", before = original, node = original.copy(label = "Different")),
            "scope" to GraphChange("changeNode", "Move existing node", before = original, node = original.copy(scope = "other")),
            "kind" to GraphChange("changeNode", "Retype existing node", before = original, node = original.copy(kind = NodeKind.Actor)),
            "port type" to GraphChange("changeNode", "Retype original port", before = original, node = original.copy(ports = original.ports.map { it.copy(type = "Different") })),
            "port removal" to GraphChange("changeNode", "Remove original port", before = original, node = original.copy(ports = emptyList())),
            "unrelated port" to GraphChange("changeNode", "Add unrelated port", before = original,
                node = original.copy(ports = original.ports + PortShape("extra", "Signal", true, PortWiring.Unused))),
            "node removal" to GraphChange("removeNode", "Remove existing node", before = original),
            "wire removal" to GraphChange("disconnect", "Remove existing wire", wire = existingWire),
            "scope facts" to GraphChange("changeGraph", "Change existing graph", graph = shape.scopes.first().copy(label = "Different")),
        )
        for ((reason, mutation) in mutations) {
            val (editor, requests, declared) = batchSetup(shape)
            val before = editor.shape
            val placement = editor.placement
            val changes = declared + mutation
            val replay = before.replay(GraphPart(changes = changes))
            assertTrue(replay.conflicts.isEmpty(), reason)
            assertNotEquals(before, replay.shape, reason)
            var commits = 0
            var saves = 0
            editor.commit = { _, _ -> commits++; error("Unrelated structure cannot commit") }
            editor.savePlacement = { _, _ -> saves++; error("Unrelated structure cannot save") }
            assertFailsWith<IllegalArgumentException>(reason) {
                CaptureConversion.batch(editor, requests, changes, before, replay.shape)
            }
            assertEquals(before, editor.shape)
            assertEquals(placement, editor.placement)
            assertTrue(editor.history.isEmpty())
            assertNull(editor.pending)
            assertEquals(0, commits)
            assertEquals(0, saves)
            assertTrue(requests.all { CaptureConversion.receipt(editor.placement, it.capture) == null })
        }
    }

    @Test fun batchCanonicalEnvelopeRetainsAllSuppliedFactsAndTypedTargetsInOneAwaitedProposal(): Unit = runBlocking {
        val before = source.copy(nodes = source.nodes.map { it.copy(attributes = mapOf("supplied" to "Unknown")) })
        val (editor, originalRequests, declared) = batchSetup(before)
        val original = before.nodes.first()
        val retained = before.nodes.last()
        val port = PortShape("converted", "Dependency", true, PortWiring.Unused)
        val wire = declared.last().wire!!.copy(provider = original.id, providerPort = port.key)
        val requests = originalRequests.dropLast(1) + originalRequests.last().copy(identity = wire.id)
        val updated = original.copy(ports = original.ports + port,
            attributes = original.attributes + mapOf("parts" to "0 of 3 done", "derived" to "supplied result"))
        val retainedFacts = retained.copy(attributes = retained.attributes + ("independent" to "retained result"))
        val changes = listOf(GraphChange("changeNode", "Supplied facts and declared port", before = original, node = updated)) +
            declared.dropLast(1) + GraphChange("connect", "Declared typed link", wire = wire) +
            GraphChange("changeNode", "Supplied independent facts", before = retained, node = retainedFacts)
        val replay = before.replay(GraphPart(changes = changes))
        assertTrue(replay.conflicts.isEmpty())
        val after = replay.shape
        val connected = port.copy(wiring = PortWiring.Connected, connections = 1)
        assertEquals(updated.copy(ports = original.ports + connected), after.node(original.id))
        assertEquals(retainedFacts, after.node(retained.id))
        assertEquals(before.scopes, after.scopes)
        assertEquals(2, after.nodes.size - before.nodes.size)
        assertEquals(listOf(wire), after.wires)
        val placement = editor.placement
        val answer = CompletableDeferred<EditReceipt>()
        var writes = 0
        var draft: GraphDraft? = null
        editor.commit = { next, expected ->
            writes++
            assertEquals(EditRevision("source:1", "presentation:1"), expected)
            draft = next
            answer.await()
        }
        editor.savePlacement = { _, _ -> error("This proposal changes topology") }
        assertFailsWith<IllegalArgumentException> { CaptureConversion.batch(editor, requests, changes) }
        assertEquals(0, writes)
        val preview = CaptureConversion.batch(editor, requests, changes, before, after)
        assertEquals(requests.map { it.identity }, preview.entries.map { it.identity })
        assertEquals("Telemetry · converted", preview.entries.last().from)
        assertEquals("Verify contract · needs", preview.entries.last().to)
        val result = async(start = CoroutineStart.UNDISPATCHED) { preview.confirm(editor) }
        try {
            assertFalse(result.isCompleted)
            assertEquals(1, writes)
            assertEquals(before, editor.shape)
            assertEquals(placement, editor.placement)
            assertEquals(after, draft!!.shape)
            assertEquals(changes, editor.pending!!.part.changes)
            assertTrue(editor.history.isEmpty())
            assertTrue(requests.all { CaptureConversion.receipt(editor.placement, it.capture) == null })
        } finally {
            answer.complete(EditReceipt.Accepted(EditRevision("source:2", "presentation:2")))
        }
        assertIs<EditReceipt.Accepted>(result.await())
        assertEquals(after, editor.shape)
        assertEquals(original.ports, editor.shape.node(original.id)!!.ports.take(original.ports.size))
        assertEquals(updated.attributes, editor.shape.node(original.id)!!.attributes)
        assertEquals(retainedFacts, editor.shape.node(retained.id))
        assertEquals(wire, editor.shape.wires.single())
        assertEquals(connected, editor.shape.node(wire.provider)!!.port(wire.providerPort, true))
        assertEquals(wire.type, editor.shape.node(wire.consumer)!!.port(wire.consumerPort, false)!!.type)
        assertEquals(requests.map { it.identity }.toSet(),
            editor.shape.nodes.filter { it.id !in before.nodes.map { node -> node.id } }.map { it.id }.toSet() + editor.shape.wires.map { it.id })
        assertEquals(1, editor.history.size)
        assertEquals(placement.captures, editor.placement.captures.filter { it.id in placement.captures.map { capture -> capture.id } })
        assertEquals(3, requests.count { CaptureConversion.receipt(editor.placement, it.capture) != null })
        assertIs<EditReceipt.Accepted>(preview.confirm(editor))
        assertEquals(1, writes)
    }

    @Test fun canonicalBatchCarriesSuppliedOrderDigestAndDefinitionFactsThroughOneAwaitedProposal(): Unit = runBlocking {
        val supplied = source.copy(
            scopes = source.scopes.mapIndexed { i, scope -> scope.copy(definitionId = "scope-definition-$i") },
            nodes = source.nodes.mapIndexed { i, value -> value.copy(definitionId = "node-definition-$i",
                ports = value.ports.mapIndexed { k, port -> port.copy(definitionId = "port-definition-$i-$k") }) },
            routes = listOf(RouteShape(source.nodes.first().id, source.nodes.last().id, "next")),
            definitionDigest = "before-digest")
        val (editor, requests, declared) = batchSetup(supplied)
        val before = editor.shape
        val changes = declared.mapIndexed { i, change -> change.copy(
            node = change.node?.let { value -> value.copy(definitionId = "created-definition-$i",
                ports = value.ports.mapIndexed { k, port -> port.copy(definitionId = "created-port-$i-$k") }) },
            wire = change.wire?.let { it.copy(definitionId = "wire-definition", sourceId = it.id) }) } +
            GraphChange("changeNode", "Complete supplied facts", before = before.nodes.first(),
                node = before.nodes.first().copy(attributes = before.nodes.first().attributes + ("parts" to "complete supplied fact")))
        val replay = before.replay(GraphPart(changes = changes))
        assertTrue(replay.conflicts.isEmpty())
        val after = replay.shape.copy(nodes = replay.shape.nodes.reversed(), definitionDigest = "canonical-after-digest")
        assertNotEquals(replay.shape, after)
        assertEquals(replay.shape.nodes.associateBy { it.id }, after.nodes.associateBy { it.id })
        assertFailsWith<IllegalArgumentException> { CaptureConversion.batch(editor, requests, changes) }
        val placement = editor.placement
        val answer = CompletableDeferred<EditReceipt>()
        var writes = 0
        var validations = 0
        var draft: GraphDraft? = null
        editor.validate = { part -> validations++; assertEquals(changes, part.changes); Verdict(true, "") }
        editor.commit = { value, expected ->
            writes++
            assertEquals(EditRevision("source:1", "presentation:1"), expected)
            draft = value
            answer.await()
        }
        editor.savePlacement = { _, _ -> error("A complete topology proposal must use commit") }
        val preview = CaptureConversion.batch(editor, requests, changes, before, after)
        val result = async(start = CoroutineStart.UNDISPATCHED) { preview.confirm(editor) }
        try {
            assertFalse(result.isCompleted)
            assertEquals(1, writes)
            assertEquals(1, validations)
            assertSame(after, draft!!.shape)
            assertEquals(after, draft!!.shape)
            assertEquals(changes, editor.pending!!.part.changes)
            assertEquals(before, editor.shape)
            assertEquals(placement, editor.placement)
            assertTrue(editor.history.isEmpty())
            assertTrue(requests.all { CaptureConversion.receipt(editor.placement, it.capture) == null })
        } finally {
            answer.complete(EditReceipt.Accepted(EditRevision("source:2", "presentation:2")))
        }
        assertIs<EditReceipt.Accepted>(result.await())
        assertSame(after, editor.shape)
        assertEquals(after.nodes.map { it.id }, editor.shape.nodes.map { it.id })
        assertEquals(after.definitionDigest, editor.shape.definitionDigest)
        assertEquals(before.scopes, editor.shape.scopes)
        assertEquals(before.routes, editor.shape.routes)
        val wire = editor.shape.wires.single()
        assertEquals(changes.firstNotNullOf { it.wire }, wire)
        assertEquals(wire.type, editor.shape.node(wire.provider)!!.port(wire.providerPort, true)!!.type)
        assertEquals(wire.type, editor.shape.node(wire.consumer)!!.port(wire.consumerPort, false)!!.type)
        for (original in before.nodes) {
            val retained = editor.shape.node(original.id)!!
            assertEquals(original.definitionId, retained.definitionId)
            assertEquals(original.ports, retained.ports)
        }
        assertEquals(1, editor.history.size)
        assertEquals(3, requests.count { CaptureConversion.receipt(editor.placement, it.capture) != null })
        assertIs<EditReceipt.Accepted>(preview.confirm(editor))
        assertEquals(1, writes)
        assertEquals(1, validations)
    }

    @Test fun canonicalBatchNeverRecomputesSuppliedDefinitionIdentitiesToAcceptAMismatch() {
        for (reason in listOf("scope", "node", "port", "wire", "created node", "duplicate node")) {
            val (editor, requests, changes) = batchSetup()
            val before = editor.shape
            val placement = editor.placement
            val replay = before.replay(GraphPart(changes = changes))
            assertTrue(replay.conflicts.isEmpty())
            val complete = replay.shape.copy(nodes = replay.shape.nodes.reversed(), definitionDigest = "supplied-after-digest")
            val after = when (reason) {
                "scope" -> complete.copy(scopes = complete.scopes.mapIndexed { i, value -> if (i == 0) value.copy(definitionId = "different") else value })
                "node" -> complete.copy(nodes = complete.nodes.map { value -> if (value.id == before.nodes.first().id) value.copy(definitionId = "different") else value })
                "port" -> complete.copy(nodes = complete.nodes.map { value -> if (value.id == before.nodes.first().id)
                    value.copy(ports = value.ports.mapIndexed { i, port -> if (i == 0) port.copy(definitionId = "different") else port }) else value })
                "wire" -> complete.copy(wires = complete.wires.map { it.copy(definitionId = "different") })
                "created node" -> complete.copy(nodes = complete.nodes.map { value -> if (value.id == requests.first().identity) value.copy(definitionId = "different") else value })
                else -> complete.copy(nodes = complete.nodes + complete.nodes.first())
            }
            assertNotEquals(complete, after, reason)
            var writes = 0
            var saves = 0
            editor.commit = { _, _ -> writes++; error("Mismatched supplied identity must not commit") }
            editor.savePlacement = { _, _ -> saves++; error("Mismatched supplied identity must not save") }
            assertFailsWith<IllegalArgumentException>(reason) { CaptureConversion.batch(editor, requests, changes, before, after) }
            assertEquals(before, editor.shape)
            assertEquals(placement, editor.placement)
            assertTrue(editor.history.isEmpty())
            assertNull(editor.pending)
            assertEquals(0, writes)
            assertEquals(0, saves)
            assertTrue(requests.all { CaptureConversion.receipt(editor.placement, it.capture) == null })
        }
    }

    @Test fun completeSnapshotConfirmationRetainsStalePermissionValidationAndHostRefusals(): Unit = runBlocking {
        for (reason in listOf("stale", "permission", "validation", "host")) {
            val (editor, requests, changes) = batchSetup()
            val before = editor.shape
            val placement = editor.placement
            val replay = before.replay(GraphPart(changes = changes))
            assertTrue(replay.conflicts.isEmpty())
            val after = replay.shape.copy(nodes = replay.shape.nodes.reversed(), definitionDigest = "supplied-after-digest")
            val preview = CaptureConversion.batch(editor, requests, changes, before, after)
            var writes = 0
            var saves = 0
            var validations = 0
            editor.commit = { draft, _ -> writes++; assertSame(after, draft.shape); EditReceipt.Refused("Host refused") }
            editor.savePlacement = { _, _ -> saves++; error("No receipt-only save may bypass refusal") }
            editor.validate = { validations++; Verdict(reason != "validation", "Domain refused") }
            if (reason == "stale") editor.editRevision = EditRevision("source:changed", "presentation:1")
            if (reason == "permission") editor.permits = { _, _ -> false }
            assertIs<EditReceipt.Refused>(preview.confirm(editor))
            assertEquals(if (reason == "host") 1 else 0, writes, reason)
            assertEquals(if (reason in listOf("validation", "host")) 1 else 0, validations, reason)
            assertEquals(0, saves)
            assertEquals(before, editor.shape)
            assertEquals(placement, editor.placement)
            assertTrue(editor.history.isEmpty())
            assertNull(editor.pending)
            assertTrue(requests.all { CaptureConversion.receipt(editor.placement, it.capture) == null })
        }
    }

}
