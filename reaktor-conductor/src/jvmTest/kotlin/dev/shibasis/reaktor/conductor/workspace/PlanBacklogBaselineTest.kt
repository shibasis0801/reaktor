package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import jdk.jfr.Configuration
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanBacklogBaselineTest {
    private fun fixture(): WorkPlan {
        val owner = BoardActor(BoardActorKind.Person, "Fixture")
        val now = System.currentTimeMillis()
        val items = buildList {
            repeat(40) { frame ->
                add(PlanItem("frame-$frame", "Delivery area $frame", kind = PlanKind.Component,
                    createdAt = now + frame, refs = listOf("file:area-$frame/")))
                repeat(39) { leaf ->
                    val agent = BoardActor(BoardActorKind.Agent, "Lane $frame", "codex:fixture-$frame")
                    add(PlanItem("item-$frame-$leaf", "Verify delivery $frame step $leaf", parent = "frame-$frame",
                        detail = "Read the declared input, keep the source of each decision and verify the result with the fixture. " +
                            "Preserve the dependency contract and report the evidence before another delivery starts.",
                        after = buildList {
                            if (leaf > 0) add("item-$frame-${leaf - 1}")
                            if (leaf > 3) add("item-$frame-${leaf - 4}")
                            if (frame > 0 && leaf > 0 && leaf % 5 == 0) add("item-${frame - 1}-$leaf")
                        },
                        refs = listOf("file:area-$frame/Step$leaf.kt", "agent:lane-${frame % 7}"),
                        kind = if (leaf % 7 == 6) PlanKind.Check else PlanKind.Task,
                        status = if (frame < 7 && leaf == 0) PlanStatus.Doing else PlanStatus.Todo,
                        lease = if (frame < 7 && leaf == 0) PlanLease(agent, now, now + 3_600_000) else null,
                        createdAt = now + frame * 39 + leaf))
                }
            }
        }
        return WorkPlan("hangar:backlog-scale", "Backlog scale fixture", items = items, revision = 1,
            createdBy = owner, createdAt = now, updatedAt = now)
    }

    @Test
    fun measuresTheFullReadNextAndOneWriteForSixteenHundredOpenItems() {
        val plan = fixture()
        assertEquals(1600, plan.items.size)
        assertEquals(40, plan.items.count { it.kind == PlanKind.Component })
        assertTrue(plan.items.all { it.status == PlanStatus.Todo || it.status == PlanStatus.Doing })
        val root = Files.createTempDirectory("plan-backlog-baseline")
        val metrics = linkedMapOf<String, JsonElement>()
        fun record(name: String, value: Number) {
            metrics[name] = buildJsonObject { put("value", value) }.getValue("value")
        }
        fun <T> timed(name: String, block: () -> T): T {
            val started = System.nanoTime()
            return block().also { record(name, (System.nanoTime() - started) / 1_000_000.0) }
        }
        try {
            val directory = Files.createDirectories(root.resolve("state/plans"))
            val stored = ConductorJson.encodeToString(WorkPlan.serializer(), plan)
            Files.writeString(directory.resolve("hangar-backlog-scale.json"), stored)
            record("items", plan.items.size)
            record("frames", 40)
            record("doing", 7)
            record("dependencies", plan.items.sumOf { it.after.size })
            record("stored_bytes", stored.toByteArray().size)
            fixtureWorkspace(root.toFile(), root.resolve("state"), emptyMap()).use { workspace ->
                val tools = agentPlanTools(workspace).associateBy { it.name }
                val reader = BoardActor(BoardActorKind.Agent, "Lane 0", "codex:fixture-0")
                val args = buildJsonObject {
                    put("thread", plan.thread)
                    put("by", AgentWorkspaceJson.encodeToJsonElement(BoardActor.serializer(), reader))
                }
                val read = timed("read_cold_ms") { tools.getValue("agent_plan_read").invoke(args) }
                val bytes = timed("read_encode_ms") { read.toString().toByteArray().size }
                record("read_bytes", bytes)
                assertEquals(1600, read.jsonObject.getValue("plan").jsonObject.getValue("items").jsonArray.size)
                timed("read_warm_ms") { tools.getValue("agent_plan_read").invoke(args) }
                val next = timed("next_cold_ms") { tools.getValue("agent_plan_next").invoke(args) }
                assertTrue(next.jsonObject["candidate"] != null && next.jsonObject["candidate"] != JsonNull)
                timed("next_warm_ms") { tools.getValue("agent_plan_next").invoke(args) }
                val patch = PlanPatch(base = plan.revision,
                    ops = listOf(PlanOp.Update("item-39-38", title = "Verified delivery 39 step 38")))
                val callback = WorkPlanStore::class.java.getDeclaredField("messages").apply { isAccessible = true }
                val original = callback.get(workspace.plans)
                val profiled: (WorkPlan, WorkPlan?, BoardActor) -> Unit = { changed, previous, by ->
                    timed("write_messages_ms") { workspace.messages.planChanged(changed, previous, by) }
                    timed("write_preemption_ms") { workspace.preemptPlans() }
                }
                callback.set(workspace.plans, profiled)
                val profile = System.getenv("HANGAR_BACKLOG_WRITE_JFR")?.let { path ->
                    Recording(Configuration.getConfiguration("profile")).apply {
                        destination = Path.of(path)
                        maxSize = 64L * 1024 * 1024
                        start()
                    }
                }
                val applied = try { timed("write_ms") { workspace.plans.write(plan.thread, patch, plan.createdBy) } }
                    finally {
                        callback.set(workspace.plans, original)
                        if (profile != null) {
                            profile.stop()
                            val counts = HashMap<String, Int>()
                            RecordingFile(profile.destination).use { file ->
                                while (file.hasMoreEvents()) {
                                    val event = file.readEvent()
                                    if (event.eventType.name != "jdk.ExecutionSample") continue
                                    val frame = event.stackTrace?.frames?.firstOrNull {
                                        it.method.type.name.startsWith("dev.shibasis")
                                    } ?: continue
                                    val name = "${frame.method.type.name}.${frame.method.name}"
                                    counts[name] = (counts[name] ?: 0) + 1
                                }
                            }
                            metrics["write_execution_samples"] = buildJsonObject {
                                counts.entries.sortedByDescending { it.value }.take(20).forEach { put(it.key, it.value) }
                            }
                            profile.close()
                        }
                    }
                assertTrue(metrics.containsKey("write_messages_ms"))
                assertTrue(metrics.containsKey("write_preemption_ms"))
                assertTrue(applied.refused.isEmpty())
                assertEquals(plan.revision + 1, applied.plan.revision)
                record("write_bytes", Files.size(directory.resolve("hangar-backlog-scale.json")))
                val rules = timed("write_rules_replay_ms") { PlanRules.apply(plan, patch, plan.createdBy, plan.createdAt) }
                timed("write_encode_replay_ms") { ConductorJson.encodeToString(WorkPlan.serializer(), rules.plan) }
                timed("analysis_report_replay_ms") { PlanAnalysis.report(plan) }
                val isolated = Files.createDirectories(root.resolve("isolated"))
                Files.writeString(isolated.resolve("hangar-backlog-scale.json"), stored)
                WorkPlanStore(isolated).use { store ->
                    assertTrue(timed("write_without_callbacks_replay_ms") {
                        store.write(plan.thread, patch, plan.createdBy)
                    }.refused.isEmpty())
                }
                var capped = plan.copy(items = emptyList(), revision = 0)
                val refused = ArrayList<PlanRefusal>()
                plan.items.map {
                    PlanOp.Add(it.title, key = it.id, kind = it.kind, parent = it.parent, refs = it.refs)
                }.chunked(100).forEach { ops ->
                    val answer = PlanRules.apply(capped, PlanPatch(title = plan.title, ops = ops), plan.createdBy, plan.createdAt)
                    capped = answer.plan
                    refused += answer.refused
                }
                record("new_plan_accepted_items", capped.items.size)
                record("new_plan_refusals", refused.size)
                assertTrue(refused.isEmpty())
                assertEquals(1600, capped.items.size)
            }
        } finally {
            val output = Path.of(System.getenv("HANGAR_BACKLOG_STORE_METRICS") ?: "build/reports/plan-backlog-baseline.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, buildJsonObject { metrics.forEach { (key, value) -> put(key, value) } }.toString())
            root.toFile().deleteTreeSafely(within = root.parent.toFile())
        }
    }
}
