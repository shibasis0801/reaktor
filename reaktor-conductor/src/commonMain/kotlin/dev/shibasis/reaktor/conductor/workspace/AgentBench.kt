package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.worklog.GraphWorkScore
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

@Serializable
enum class BenchTrack(val title: String, val summary: String) {
    AtlasInvestigation(
        "Atlas & Context",
        "Read-only symbol navigation, blast-radius analysis, and contract verification without context bloat.",
    ),
    BlindReview(
        "Blind Defect Review",
        "Cross-vendor defect detection on planted issues with zero file or line hints.",
    ),
    ScopedBuild(
        "Scoped KMP Build",
        "Multiplatform Kotlin implementation, focused unit check, and independent cross-vendor review.",
    ),
    TieredEscalation(
        "Tiered Escalation",
        "Fast worker seat escalating to a frontier model only on check failure or review repair.",
    ),
}

@Serializable
enum class BenchQuotaTier(val title: String, val targetTokens: Long, val summary: String) {
    Micro(
        "Micro",
        90_000L,
        "Low-cost smoke and read-only review (~50k–120k tokens). Safe for frequent model checks.",
    ),
    Standard(
        "Standard",
        220_000L,
        "Scoped write, narrow unit check, and cross-vendor review (~140k–260k tokens).",
    ),
    Extended(
        "Extended",
        480_000L,
        "Tiered escalation and multi-round repair trials (~250k–600k tokens).",
    ),
}

@Serializable
enum class BenchQuotaImpact(val title: String) {
    Low("Low quota"),
    Moderate("Moderate quota"),
    Heavy("Heavy quota"),
}

@Serializable
data class AgentBenchDefect(val id: String, val kind: String, val path: String, val from: Int, val through: Int = from)

@Serializable
data class AgentBenchConfiguration(
    val id: String,
    val slots: Map<String, TaskSeat>,
    val estimatedTokens: Long? = null,
    val label: String = "",
    val description: String = "",
) {
    val displayLabel: String get() = label.ifBlank { id }
}

@Serializable
data class AgentBenchScenario(
    val id: String,
    val title: String,
    val revisions: Map<String, String>,
    val draft: TaskGraphDraft,
    val defects: List<AgentBenchDefect> = emptyList(),
    val version: Int = 1,
    val suite: String = "reaktor-plus",
    val track: BenchTrack = BenchTrack.ScopedBuild,
    val quotaTier: BenchQuotaTier = BenchQuotaTier.Standard,
    val summary: String = "",
    val whyItMatters: String = "",
    val presets: List<AgentBenchConfiguration> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class AgentBenchBinding(
    val scenario: AgentBenchScenario,
    val scenarioRevision: String,
    val configuration: String,
    val seats: Map<String, ResolvedTaskSeat>,
    val repeat: Int,
    val repeats: Int,
    val ownerBuild: String,
    val estimatedTokens: Long?,
    val definition: String = "",
)

@Serializable
data class AgentBenchScenarioEntry(
    val scenario: AgentBenchScenario,
    val revision: String,
    val plantedDefectCount: Int = 0,
)

@Serializable
data class AgentBenchRun(val graph: String, val status: TaskGraphStatus, val binding: AgentBenchBinding, val result: AgentBenchResult? = null)

@Serializable
data class AgentBenchCatalog(val scenarios: List<AgentBenchScenarioEntry>, val runs: List<AgentBenchRun>)

@Serializable
data class AgentBenchLensScore(
    val profile: String,
    val revision: Long?,
    val provider: RuntimeKind?,
    val kind: String,
    val expected: Int,
    val confirmed: Int,
    val missed: Int,
    val unmatchedConfirmed: Int,
    val unconfirmed: Int,
)

@Serializable
data class AgentBenchResult(
    val graph: String,
    val revision: Long,
    val measuredAt: Long,
    val binding: AgentBenchBinding,
    val score: GraphWorkScore,
    val checksPassed: Boolean?,
    val acceptedCandidates: Int,
    val candidates: Int,
    val repairAttempts: Int,
    val lenses: List<AgentBenchLensScore>,
    val comparable: Boolean,
    val notes: List<String>,
)

@Serializable
data class BenchQuotaEstimate(
    val estimatedTokensPerRun: Long,
    val envelopeCapPerRun: Long?,
    val totalEstimatedTokens: Long,
    val totalEnvelopeCapTokens: Long?,
    val repeats: Int,
    val agentSeatCount: Int,
    val writeTaskCount: Int,
    val checkTaskCount: Int,
    val providers: Set<RuntimeKind>,
    val impact: BenchQuotaImpact,
)

@Serializable
data class BenchStanding(
    val configuration: String,
    val label: String,
    val scenarioId: String? = null,
    val providers: Set<RuntimeKind> = emptySet(),
    val primaryProvider: RuntimeKind? = null,
    val primaryModel: String? = null,
    val reviewerProvider: RuntimeKind? = null,
    val hasEscalation: Boolean = false,
    val totalRuns: Int = 0,
    val measuredRuns: Int = 0,
    val comparableRuns: Int = 0,
    val nonComparableRuns: Int = 0,
    val openRuns: Int = 0,
    val acceptedRuns: Int = 0,
    val candidateRuns: Int = 0,
    val checksPassedRuns: Int = 0,
    val checksEvaluatedRuns: Int = 0,
    val expectedDefects: Int = 0,
    val confirmedDefects: Int = 0,
    val unmatchedConfirmedDefects: Int = 0,
    val totalRepairAttempts: Int = 0,
    val medianInputTokens: Long? = null,
    val meanInputTokens: Long? = null,
    val meanCachedShare: Double? = null,
    val meanOutputTokens: Long? = null,
    val meanReasoningTokens: Long? = null,
    val maxPeakContextPercent: Double? = null,
    val totalCompactions: Int = 0,
    val medianWallMillis: Long? = null,
    val medianWorkingMillis: Long? = null,
    val meanReportedCostUsd: Double? = null,
    val meanEffectiveCostUsd: Double? = null,
    val usesEstimatedCost: Boolean = false,
    val tokensPerAccepted: Long? = null,
    val tokensPerPassingCheck: Long? = null,
    val costPerAcceptedUsd: Double? = null,
) {
    val acceptanceRate: Double?
        get() = if (candidateRuns > 0) acceptedRuns.toDouble() / candidateRuns.toDouble() else null
    val checkPassRate: Double?
        get() = if (checksEvaluatedRuns > 0) checksPassedRuns.toDouble() / checksEvaluatedRuns.toDouble() else null
    val defectRecall: Double?
        get() = if (expectedDefects > 0) confirmedDefects.toDouble() / expectedDefects.toDouble() else null
}

object ReaktorPlusBench {
    val NativeProviders: Set<RuntimeKind> = setOf(RuntimeKind.ClaudeCode, RuntimeKind.Codex, RuntimeKind.Gemini)

    fun presetsFor(scenario: AgentBenchScenario): List<AgentBenchConfiguration> {
        if (scenario.presets.isNotEmpty()) return scenario.presets
        val slots = TaskGraphTemplates.slots(scenario.draft)
        if (slots.isEmpty()) return emptyList()
        val budget = scenario.draft.envelope.tokens ?: scenario.quotaTier.targetTokens
        fun bind(
            id: String,
            label: String,
            description: String,
            writer: TaskSeat,
            reviewer: TaskSeat,
            panel: List<String> = emptyList(),
            tokens: Long = budget,
        ): AgentBenchConfiguration = AgentBenchConfiguration(
            id = id,
            label = label,
            description = description,
            estimatedTokens = tokens,
            slots = slots.mapValues { (_, base) ->
                when {
                    base.access == TaskAccess.Write -> writer.copy(access = TaskAccess.Write)
                    base.lenses.isNotEmpty() -> reviewer.copy(
                        access = TaskAccess.Read,
                        lenses = panel.ifEmpty { base.lenses },
                        escalate = emptyList(),
                    )
                    else -> reviewer.copy(access = base.access ?: TaskAccess.Read, escalate = emptyList())
                }
            },
        )
        val triPanel = listOf("codex-correctness", "claude-correctness", "gemini-correctness")
        val hasWrites = slots.values.any { it.access == TaskAccess.Write }
        return buildList {
            add(
                bind(
                    id = "codex-sol-claude-opus",
                    label = "Codex Sol · medium + Claude Opus review",
                    description = "Fast Codex Sol implementation verified by Claude Opus 5.5 and tri-vendor lenses.",
                    writer = TaskSeat(RuntimeKind.Codex, "gpt-6.1-sol", "medium", TaskAccess.Write),
                    reviewer = TaskSeat(RuntimeKind.ClaudeCode, "claude-opus-5-5", "medium", TaskAccess.Read),
                    panel = triPanel,
                )
            )
            add(
                bind(
                    id = "claude-opus-gemini-carbon",
                    label = "Claude Opus · medium + Gemini Carbon review",
                    description = "Claude Opus 5.5 primary seat paired with Gemini 4 Carbon cross-vendor verification.",
                    writer = TaskSeat(RuntimeKind.ClaudeCode, "claude-opus-5-5", "medium", TaskAccess.Write),
                    reviewer = TaskSeat(RuntimeKind.Gemini, "gemini-4-carbon-high", "high", TaskAccess.Read),
                    panel = triPanel,
                )
            )
            add(
                bind(
                    id = "gemini-carbon-codex-sol",
                    label = "Gemini Carbon · high + Codex Sol review",
                    description = "Gemini 4 Carbon primary seat paired with Codex Sol 6.1 independent review.",
                    writer = TaskSeat(RuntimeKind.Gemini, "gemini-4-carbon-high", "high", TaskAccess.Write),
                    reviewer = TaskSeat(RuntimeKind.Codex, "gpt-6.1-sol", "medium", TaskAccess.Read),
                    panel = triPanel,
                )
            )
            if (hasWrites) {
                add(
                    bind(
                        id = "tiered-terra-sol-argon",
                        label = "Tiered Terra -> Sol + Gemini Argon review",
                        description = "Quota-saving ladder: Terra 6.1 tries first, escalating to Sol 6.1 only on repair, reviewed by Gemini 4 Argon.",
                        writer = TaskSeat(
                            agent = RuntimeKind.Codex,
                            model = "gpt-6.1-terra",
                            effort = "medium",
                            access = TaskAccess.Write,
                            escalate = listOf(TaskSeat(RuntimeKind.Codex, "gpt-6.1-sol", "high", TaskAccess.Write)),
                        ),
                        reviewer = TaskSeat(RuntimeKind.Gemini, "gemini-4-argon-high", "high", TaskAccess.Read),
                        panel = listOf("codex-correctness", "gemini-correctness"),
                        tokens = (budget * 3L / 4L).coerceAtLeast(60_000L),
                    )
                )
            }
        }
    }

    fun estimateQuota(
        entry: AgentBenchScenarioEntry,
        configuration: AgentBenchConfiguration,
        repeats: Int,
    ): BenchQuotaEstimate {
        val count = repeats.coerceIn(1, 10)
        val perRun = (configuration.estimatedTokens ?: entry.scenario.draft.envelope.tokens ?: entry.scenario.quotaTier.targetTokens)
            .coerceAtLeast(1_000L)
        val cap = entry.scenario.draft.envelope.tokens
        val total = perRun * count.toLong()
        val tasks = entry.scenario.draft.tasks
        val providers = buildSet {
            configuration.slots.values.forEach { seat ->
                seat.agent?.let(::add)
                seat.escalate.mapNotNull { it.agent }.forEach(::add)
                seat.lenses.forEach { lens ->
                    when {
                        lens.startsWith("codex-") -> add(RuntimeKind.Codex)
                        lens.startsWith("claude-") -> add(RuntimeKind.ClaudeCode)
                        lens.startsWith("gemini-") -> add(RuntimeKind.Gemini)
                    }
                }
            }
        }
        val impact = when {
            total <= 140_000L -> BenchQuotaImpact.Low
            total <= 320_000L -> BenchQuotaImpact.Moderate
            else -> BenchQuotaImpact.Heavy
        }
        return BenchQuotaEstimate(
            estimatedTokensPerRun = perRun,
            envelopeCapPerRun = cap,
            totalEstimatedTokens = total,
            totalEnvelopeCapTokens = cap?.times(count.toLong()),
            repeats = count,
            agentSeatCount = configuration.slots.size,
            writeTaskCount = tasks.count { it.seat?.access == TaskAccess.Write || it.alternatives != null },
            checkTaskCount = tasks.count { it.kind == TaskKind.Check || it.alternatives != null },
            providers = providers,
            impact = impact,
        )
    }

    fun standings(
        catalog: AgentBenchCatalog,
        scenarioId: String? = null,
        comparableOnly: Boolean = true,
    ): List<BenchStanding> {
        val presetsByKey = catalog.scenarios.flatMap { entry ->
            presetsFor(entry.scenario).map { (entry.scenario.id to it.id) to it }
        }.toMap()
        val filtered = catalog.runs.filter { scenarioId == null || it.binding.scenario.id == scenarioId }
        val grouped = filtered.groupBy { run ->
            if (scenarioId != null) run.binding.configuration else run.binding.configuration
        }
        return grouped.map { (configId, runs) ->
            val sample = runs.first()
            val preset = presetsByKey[sample.binding.scenario.id to configId]
                ?: catalog.scenarios.asSequence().flatMap { presetsFor(it.scenario).asSequence() }.firstOrNull { it.id == configId }
            val resolvedSeats = sample.binding.seats
            val writerSeat = resolvedSeats.values.firstOrNull { it.seat.access == TaskAccess.Write } ?: resolvedSeats.values.firstOrNull()
            val reviewSeat = resolvedSeats.entries.firstOrNull { (key, seat) ->
                seat.seat.access == TaskAccess.Read && (key.contains("review") || key.contains("verify") || seat.lenses.isNotEmpty() || seat != writerSeat)
            }?.value
            val providers = buildSet {
                resolvedSeats.values.forEach { resolved ->
                    resolved.seat.agent?.let(::add)
                    resolved.seat.escalate.mapNotNull { it.agent }.forEach(::add)
                    resolved.lenses.mapNotNull { it.seat.agent }.forEach(::add)
                }
            }
            val terminalStates = setOf(TaskGraphStatus.Finished, TaskGraphStatus.Failed, TaskGraphStatus.Cancelled)
            val openRuns = runs.count { it.status !in terminalStates }
            val measured = runs.mapNotNull { it.result }
            val comparable = measured.filter { it.comparable }
            val nonComparable = measured.size - comparable.size
            val pool = if (comparableOnly) comparable else measured
            val terminalPool = pool.filter { runResult ->
                runs.firstOrNull { it.graph == runResult.graph }?.status in terminalStates
            }.ifEmpty { pool }

            val acceptedRuns = terminalPool.count { it.acceptedCandidates > 0 }
            val candidateRuns = terminalPool.count { it.candidates > 0 || it.binding.seats.values.any { seat -> seat.seat.access == TaskAccess.Write } }
            val evaluatedChecks = terminalPool.filter { it.checksPassed != null }
            val passedChecks = evaluatedChecks.count { it.checksPassed == true }
            val nonUnclassifiedLenses = terminalPool.flatMap { result -> result.lenses.filter { it.kind != "unclassified" } }
            val unclassifiedLenses = terminalPool.flatMap { result -> result.lenses.filter { it.kind == "unclassified" } }
            val expectedDefects = nonUnclassifiedLenses.sumOf { it.expected }
            val confirmedDefects = nonUnclassifiedLenses.sumOf { it.confirmed }
            val unmatchedConfirmed = unclassifiedLenses.sumOf { it.unmatchedConfirmed }

            val inputs = terminalPool.mapNotNull { it.score.total.inputTokens }
            val outputs = terminalPool.mapNotNull { it.score.total.outputTokens }
            val reasonings = terminalPool.mapNotNull { it.score.total.reasoningTokens }
            val cachedShares = terminalPool.mapNotNull { res ->
                val inp = res.score.total.inputTokens?.takeIf { it > 0L } ?: return@mapNotNull null
                val cached = res.score.total.cachedInputTokens ?: return@mapNotNull null
                (cached.toDouble() / inp.toDouble()).coerceIn(0.0, 1.0)
            }
            val peakPercents = terminalPool.mapNotNull { it.score.total.peakContextPercent }
            val walls = terminalPool.mapNotNull { it.score.total.wallMillis }
            val workings = terminalPool.mapNotNull { it.score.total.workingMillis }
            val reportedCosts = terminalPool.mapNotNull { it.score.total.costUsd }
            val effectiveCosts = terminalPool.mapNotNull { it.score.total.costUsd ?: it.score.total.estimatedCostUsd }
            val usesEstimated = terminalPool.any { it.score.total.costUsd == null && it.score.total.estimatedCostUsd != null }

            val totalCombinedTokens = terminalPool.mapNotNull { res ->
                val inp = res.score.total.inputTokens ?: return@mapNotNull null
                inp + (res.score.total.outputTokens ?: 0L)
            }
            val tokenSum = totalCombinedTokens.takeIf { it.isNotEmpty() }?.sum()
            val costSum = effectiveCosts.takeIf { it.isNotEmpty() }?.sum()

            BenchStanding(
                configuration = configId,
                label = preset?.displayLabel ?: configId,
                scenarioId = scenarioId,
                providers = providers,
                primaryProvider = writerSeat?.seat?.agent,
                primaryModel = writerSeat?.seat?.model,
                reviewerProvider = reviewSeat?.seat?.agent,
                hasEscalation = resolvedSeats.values.any { it.seat.escalate.isNotEmpty() },
                totalRuns = runs.size,
                measuredRuns = measured.size,
                comparableRuns = comparable.size,
                nonComparableRuns = nonComparable,
                openRuns = openRuns,
                acceptedRuns = acceptedRuns,
                candidateRuns = candidateRuns,
                checksPassedRuns = passedChecks,
                checksEvaluatedRuns = evaluatedChecks.size,
                expectedDefects = expectedDefects,
                confirmedDefects = confirmedDefects,
                unmatchedConfirmedDefects = unmatchedConfirmed,
                totalRepairAttempts = terminalPool.sumOf { it.repairAttempts },
                medianInputTokens = medianLong(inputs),
                meanInputTokens = inputs.takeIf { it.isNotEmpty() }?.average()?.roundToLong(),
                meanCachedShare = cachedShares.takeIf { it.isNotEmpty() }?.average(),
                meanOutputTokens = outputs.takeIf { it.isNotEmpty() }?.average()?.roundToLong(),
                meanReasoningTokens = reasonings.takeIf { it.isNotEmpty() }?.average()?.roundToLong(),
                maxPeakContextPercent = peakPercents.maxOrNull(),
                totalCompactions = terminalPool.sumOf { it.score.total.compactions },
                medianWallMillis = medianLong(walls),
                medianWorkingMillis = medianLong(workings),
                meanReportedCostUsd = reportedCosts.takeIf { it.isNotEmpty() }?.average(),
                meanEffectiveCostUsd = effectiveCosts.takeIf { it.isNotEmpty() }?.average(),
                usesEstimatedCost = usesEstimated,
                tokensPerAccepted = if (acceptedRuns > 0 && tokenSum != null) tokenSum / acceptedRuns.toLong() else null,
                tokensPerPassingCheck = if (passedChecks > 0 && tokenSum != null) tokenSum / passedChecks.toLong() else null,
                costPerAcceptedUsd = if (acceptedRuns > 0 && costSum != null) costSum / acceptedRuns.toDouble() else null,
            )
        }.sortedWith(
            compareByDescending<BenchStanding> { it.acceptedRuns }
                .thenByDescending { it.checksPassedRuns }
                .thenByDescending { it.confirmedDefects }
                .thenBy { it.tokensPerAccepted ?: it.tokensPerPassingCheck ?: it.medianInputTokens ?: Long.MAX_VALUE }
                .thenBy { it.configuration }
        )
    }

    private fun medianLong(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2L
    }
}
