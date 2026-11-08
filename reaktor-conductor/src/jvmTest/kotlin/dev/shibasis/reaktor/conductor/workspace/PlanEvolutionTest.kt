package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanEvolutionTest {
    private val person = BoardActor(BoardActorKind.Person, "Shibasis", "hangar:test")
    private val agent = BoardActor(BoardActorKind.Agent, "codex", "codex:test")
    private fun draw(vararg ops: PlanOp): WorkPlan {
        val patch = PlanPatch(title = "Plan evolution", ops = ops.toList())
        val applied = PlanRules.apply(PlanRules.start("hangar:test", patch, person, 1), patch, person, 2)
        assertTrue(applied.refused.isEmpty(), applied.refused.toString())
        return applied.plan
    }

    @Test fun priorityDefaultsForOldPlansAndSurvivesAddAndUpdate() {
        assertEquals(PlanPriority.Normal, ConductorJson.decodeFromString(PlanItem.serializer(), "{\"id\":\"old\",\"title\":\"Old item\"}").priority)
        val plan = draw(PlanOp.Add("Build", key = "build", priority = PlanPriority.High))
        assertEquals(PlanPriority.High, plan.item("build")?.priority)
        val updated = PlanRules.apply(plan, PlanPatch(ops = listOf(PlanOp.Update("build", priority = PlanPriority.Urgent))), person, 3).plan
        val restored = ConductorJson.decodeFromString(WorkPlan.serializer(), ConductorJson.encodeToString(WorkPlan.serializer(), updated))
        assertEquals(PlanPriority.Urgent, restored.item("build")?.priority)
        assertEquals("Urgent", PlanGraph.shape(listOf(restored)).nodes.first { it.id.endsWith("#build") }.attributes["priority"])
    }

    @Test fun readyTasksRespectDependenciesBeforePriorityAndKeepEqualPrioritiesStable() {
        val plan = draw(PlanOp.Add("Low", key = "low", priority = PlanPriority.Low),
            PlanOp.Add("Normal", key = "normal"), PlanOp.Add("High", key = "high", priority = PlanPriority.High),
            PlanOp.Add("Urgent", key = "urgent", priority = PlanPriority.Urgent),
            PlanOp.Add("Blocked urgent", key = "blocked", after = listOf("low"), priority = PlanPriority.Urgent),
            PlanOp.Add("Another high", key = "high-again", priority = PlanPriority.High),
            PlanOp.Add("Finished", key = "done", status = PlanStatus.Done))
        assertEquals(listOf("low", "urgent", "high", "high-again", "normal"), plan.ready().map { it.id })
        val finished = PlanRules.apply(plan, PlanPatch(ops = listOf(PlanOp.Status("low", PlanStatus.Done))), person, 3).plan
        assertEquals(listOf("urgent", "blocked"), finished.ready().take(2).map { it.id })
    }

    @Test fun droppingRequiresAReasonAndAValidReplacement() {
        val plan = draw(PlanOp.Add("Old route", key = "old"), PlanOp.Add("Better route", key = "new"))
        listOf(PlanOp.Status("old", PlanStatus.Dropped), PlanOp.Status("old", PlanStatus.Dropped, "  "),
            PlanOp.Status("old", PlanStatus.Dropped, "Simpler", "old"), PlanOp.Status("old", PlanStatus.Dropped, "Simpler", "missing"),
            PlanOp.Add("Already closed", key = "closed", status = PlanStatus.Dropped)).forEach { op ->
            val refused = PlanRules.apply(plan, PlanPatch(ops = listOf(op)), person, 3)
            assertEquals(1, refused.refused.size)
            assertEquals(plan, refused.plan)
        }
        val closed = PlanRules.apply(plan, PlanPatch(ops = listOf(PlanOp.Status("old", PlanStatus.Dropped, "  Uses the existing path  ", "new"))), agent, 3).plan
        val restored = ConductorJson.decodeFromString(WorkPlan.serializer(), ConductorJson.encodeToString(WorkPlan.serializer(), closed))
        assertEquals(2, restored.items.size)
        assertEquals("Uses the existing path", restored.item("old")?.reason)
        assertEquals("new", restored.item("old")?.replacement)
        assertEquals(PlanStatus.Dropped, restored.item("old")?.status)
        val node = PlanGraph.shape(listOf(restored)).nodes.first { it.id.endsWith("#old") }
        assertEquals("Uses the existing path", node.attributes["reason"])
        assertEquals("new", node.attributes["replacement"])
        assertEquals(1, PlanRules.apply(restored, PlanPatch(ops = listOf(PlanOp.Update("old", reason = ""))), person, 4).refused.size)
    }

    @Test fun agentsKeepPlannedHistoryAndLegacyClosedItemsStillRead() {
        val old = ConductorJson.decodeFromString(PlanItem.serializer(), "{\"id\":\"old\",\"title\":\"Old item\",\"status\":\"Dropped\"}")
        assertEquals("", old.reason)
        val plan = draw(PlanOp.Add("Keep history", key = "history"))
        val refused = PlanRules.apply(plan, PlanPatch(ops = listOf(PlanOp.Remove("history"))), agent, 3)
        assertEquals(plan.items, refused.plan.items)
        assertTrue(refused.refused.single().reason.contains("reason"))
    }

    @Test fun sharedMarkdownAndPreviewLinksPersistAcrossWritersAndReopening() {
        val directory = java.nio.file.Files.createTempDirectory("plan-context")
        try {
            val thread = "hangar:context"
            val note = "    indented code\n\n" + "Context for the agent.\n".repeat(300)
            val links = listOf(PlanPreview("http://localhost:3000/result", "Result"), PlanPreview("https://example.com/review?q=one#details", "Review"))
            val created = WorkPlanStore(directory).write(thread, PlanPatch(title = "Shared context", ops = listOf(
                PlanOp.Add("Work", key = "work", detail = note, previews = links))), person)
            assertTrue(created.refused.isEmpty())
            val store = WorkPlanStore(directory)
            assertEquals(note, store.read(thread).plan.item("work")?.detail)
            assertEquals(links, store.read(thread).plan.item("work")?.previews)
            val renamed = store.write(thread, PlanPatch(base = created.plan.revision, ops = listOf(PlanOp.Update("work", title = "Renamed"))), agent)
            assertTrue(renamed.refused.isEmpty())
            assertEquals(note, renamed.plan.item("work")?.detail)
            assertEquals(links, renamed.plan.item("work")?.previews)
            val cleared = store.write(thread, PlanPatch(base = renamed.plan.revision, ops = listOf(PlanOp.Update("work", detail = "", previews = emptyList()))), person)
            assertTrue(cleared.refused.isEmpty())
            val reopened = WorkPlanStore(directory).read(thread).plan.item("work")
            assertEquals("", reopened?.detail)
            assertEquals(emptyList(), reopened?.previews)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun legacyPlanItemsAndPatchesKeepContextDefaults() {
        val legacy = ConductorJson.decodeFromString(PlanItem.serializer(), """{"id":"old","title":"Old","detail":"Existing context"}""")
        assertEquals("Existing context", legacy.detail)
        assertEquals(emptyList(), legacy.previews)
        val add = ConductorJson.decodeFromString(PlanOp.serializer(), """{"type":"add","title":"Legacy add"}""") as PlanOp.Add
        val update = ConductorJson.decodeFromString(PlanOp.serializer(), """{"type":"update","item":"old","title":"Renamed"}""") as PlanOp.Update
        assertEquals(emptyList(), add.previews)
        assertEquals(null, update.previews)
    }

    @Test fun invalidPreviewAttachmentsAndOversizedNotesLeaveTheItemUnchanged() {
        val plan = draw(PlanOp.Add("Work", key = "work", detail = "Keep", previews = listOf(PlanPreview("https://example.com"))))
        val invalid = listOf("javascript:alert(1)", "data:text/html,hello", "file:///tmp/result.html", "/relative",
            "https://user:password@example.com", "https://example%2ecom", "https://example.com:nope",
            "http://localhost:0", "https://example.com:65536", "http://localhost:123456", "https://[not-ipv6]",
            "https://", "http://:3000", "http://[", "https://example.com/a b", "https://example.com/\\path", "https://example.com/a\nb")
            .map { listOf(PlanPreview(it)) } + listOf(
                listOf(PlanPreview("https://example.com/" + "x".repeat(2_048))),
                listOf(PlanPreview("https://example.com", "x".repeat(201))),
                List(PlanRules.MaxPreviews + 1) { PlanPreview("https://example.com/$it") })
        invalid.forEach { previews ->
            val refused = PlanRules.apply(plan, PlanPatch(ops = listOf(PlanOp.Update("work", title = "Uncommitted", previews = previews))), person, 3)
            assertEquals(1, refused.refused.size, previews.toString())
            assertEquals(plan, refused.plan)
        }
        val oversized = PlanRules.apply(plan, PlanPatch(ops = listOf(PlanOp.Update("work", detail = "x".repeat(PlanRules.MaxNote + 1)))), person, 3)
        assertEquals(1, oversized.refused.size)
        assertEquals(plan, oversized.plan)
        val invalidAdd = PlanRules.apply(plan, PlanPatch(ops = listOf(PlanOp.Add("Unsafe", key = "unsafe", previews = listOf(PlanPreview("file:///tmp/result.html"))))), agent, 3)
        assertEquals(1, invalidAdd.refused.size)
        assertEquals(plan, invalidAdd.plan)
    }

    @Test fun previewsAcceptLocalAndSecureWebResultsAndNormalizeTheirLabels() {
        val links = listOf(PlanPreview("  http://127.0.0.1:8080/preview  ", " Result "),
            PlanPreview("http://[::1]:3000"), PlanPreview("HTTPS://example.com/review?one=two#part"),
            PlanPreview("http://localhost:65535"), PlanPreview("https://example.com:443"))
        val item = draw(PlanOp.Add("Work", key = "work", previews = links)).item("work")
        assertEquals(links.map { it.copy(url = it.url.trim(), title = it.title.trim()) }, item?.previews)
    }

    @Test fun aStaleContextWriteCannotReplaceAnotherWritersNoteOrPreviews() {
        val initial = draw(PlanOp.Add("Work", key = "work", detail = "Initial"))
        val updated = PlanRules.apply(initial, PlanPatch(base = initial.revision, ops = listOf(
            PlanOp.Update("work", detail = "Human correction", previews = listOf(PlanPreview("https://example.com/correct"))))), person, 3).plan
        val stale = PlanRules.apply(updated, PlanPatch(base = initial.revision, ops = listOf(
            PlanOp.Update("work", detail = "Stale agent context", previews = emptyList()))), agent, 4)
        assertEquals(1, stale.refused.size)
        assertTrue(stale.refused.single().reason.contains("changed at revision"))
        assertEquals(updated, stale.plan)
    }

    @Test fun anAttachedAgentsBriefIncludesSharedNotesAndPreviewLinks() {
        val plan = draw(PlanOp.Add("Work", key = "work", detail = "## Why\nHuman context", previews = listOf(PlanPreview("http://localhost:3000", "Result"))))
        val seat = PlanSeat("s1", "codex", listOf("work"), person, 1)
        val brief = PlanBrief.seat(plan, seat, "Codex", "")
        assertTrue("## Why\nHuman context" in brief)
        assertTrue("Results media: Result — http://localhost:3000" in brief)
        assertTrue("previews" in brief)
    }
}
