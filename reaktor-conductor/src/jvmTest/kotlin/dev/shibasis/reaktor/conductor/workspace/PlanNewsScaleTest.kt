package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanNewsScaleTest {
    @Test fun anAgentsNewsCheckKeepsTheSixteenHundredItemBacklogOutOfItsAnswer() {
        val root = Files.createTempDirectory("plan-news-scale")
        val metrics = linkedMapOf<String, JsonElement>()
        fun <T> timed(name: String, block: () -> T): T {
            val start = System.nanoTime()
            return block().also { metrics[name] = JsonPrimitive((System.nanoTime() - start) / 1_000_000.0) }
        }
        try {
            fixtureWorkspace(root.toFile(), root.resolve("state"), emptyMap()).use { workspace ->
                val person = BoardActor(BoardActorKind.Person, "Fixture owner")
                val reader = BoardActor(BoardActorKind.Agent, "lane-0", "codex:news-scale")
                val profile = workspace.profiles.save(AgentProfile("lane-0", "lane-0", RuntimeKind.Codex), person)
                workspace.bindIdentity(reader, profile.id, workspace.identity(reader).bindingRevision)
                val canonical = workspace.planIdentity(reader)
                val now = System.currentTimeMillis()
                val items = buildList {
                    repeat(40) { frame ->
                        add(PlanItem("frame-$frame", "Delivery area $frame", kind = PlanKind.Component,
                            refs = listOf("file:area-$frame/", "agent:lane-${frame % 7}")))
                        repeat(39) { leaf ->
                            add(PlanItem("item-$frame-$leaf", "Verify delivery $frame step $leaf", parent = "frame-$frame",
                                detail = "Read the declared input, keep the source of each decision and verify the result with the fixture. " +
                                    "Preserve the dependency contract and report the evidence before another delivery starts.",
                                after = buildList {
                                    if (leaf > 0) add("item-$frame-${leaf - 1}")
                                    if (leaf > 3) add("item-$frame-${leaf - 4}")
                                    if (frame > 0 && leaf > 0 && leaf % 5 == 0) add("item-${frame - 1}-$leaf")
                                }, refs = listOf("file:area-$frame/Step$leaf.kt", "agent:lane-${frame % 7}"),
                                status = if (frame == 0 && leaf == 0) PlanStatus.Doing else PlanStatus.Todo,
                                lease = if (frame == 0 && leaf == 0) PlanLease(canonical, now, now + 3_600_000) else null,
                                createdAt = now + frame * 39 + leaf))
                        }
                    }
                }
                val plan = WorkPlan("hangar:news-scale", "Backlog news fixture", items = items, revision = 1,
                    createdBy = person, createdAt = now, updatedAt = now)
                plan.items.chunked(100).forEach { batch ->
                    workspace.plans.write(plan.thread, PlanPatch(title = plan.title, ops = batch.map { item ->
                        PlanOp.Add(title = item.title, key = item.id, kind = item.kind, parent = item.parent,
                            detail = item.detail, after = item.after, refs = item.refs, status = item.status)
                    }), person)
                }
                val baseline = workspace.plans.plan(plan.thread)!!.revision
                workspace.plans.write(plan.thread, PlanPatch(base = baseline,
                    ops = listOf(PlanOp.Update("item-0-0", title = "Verified delivery 0 step 0"))), person)
                val read = agentPlanTools(workspace).single { it.name == "agent_plan_read" }
                fun args(actor: BoardActor) = buildJsonObject {
                    put("thread", plan.thread); put("since", baseline)
                    put("by", AgentWorkspaceJson.encodeToJsonElement(BoardActor.serializer(), actor))
                }
                val full = timed("full_news_ms") { read.invoke(args(person)) }.jsonObject
                metrics["full_news_bytes"] = JsonPrimitive(full.toString().encodeToByteArray().size)
                assertEquals(1600, full.getValue("plan").jsonObject.getValue("items").jsonArray.size)
                val scoped = timed("agent_news_cold_ms") { read.invoke(args(reader)) }.jsonObject
                metrics["agent_news_bytes"] = JsonPrimitive(scoped.toString().encodeToByteArray().size)
                metrics["agent_news_items"] = JsonPrimitive(scoped.getValue("plan").jsonObject.getValue("items").jsonArray.size)
                timed("agent_news_warm_ms") { read.invoke(args(reader)) }
                assertTrue(scoped.toString().encodeToByteArray().size <= PlanReadPage.DefaultBytes)
                val delivered = scoped.getValue("plan").jsonObject.getValue("items").jsonArray
                assertTrue(delivered.size in 1..1599)
                assertTrue(delivered.any { it.jsonObject.getValue("id").jsonPrimitive.content == "item-0-0" })
                assertTrue(scoped.getValue("news").jsonArray.isNotEmpty())
            }
        } finally {
            val output = Path.of(System.getenv("HANGAR_NEWS_METRICS") ?: "build/reports/plan-news-scale.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, JsonObject(metrics).toString())
            root.toFile().deleteTreeSafely(within = root.parent.toFile())
        }
    }
}
