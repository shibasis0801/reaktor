package dev.shibasis.reaktor.conductor

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Everything one turn added to the thread, plus the thread it produced. */
data class ConductorResult(
    val thread: ThreadDocument,
    val added: List<ThreadEvent>,
) {
    /** The answer a caller should show: the synthesis if there is one, else the last event. */
    val answer: ThreadEvent?
        get() = added.lastOrNull { it.kind == EventKind.Synthesis } ?: added.lastOrNull()
}

/**
 * Runs a [Protocol] over a [ThreadDocument].
 *
 * The conductor owns the conversation and the topology; harnesses only answer questions. Results
 * are appended in roster order rather than completion order, so the same run over the same thread
 * produces the same document and diffs stay meaningful.
 */
class Conductor(
    private val runtimes: Map<RuntimeKind, AgentRuntime>,
    private val compiler: ContextCompiler = DefaultContextCompiler(),
    private val clock: () -> Long = { 0L },
    private val idFactory: (Int) -> EventId = { EventId("e$it") },
) {
    suspend fun run(
        thread: ThreadDocument,
        prompt: String,
        protocol: Protocol,
        workingDirectory: String,
        author: Author = Author.Human(),
        context: ContextPacket? = null,
        resumeProviderSession: Boolean = false,
        onCheckpoint: (ThreadDocument) -> Unit = {},
        /**
         * Called with the live session for a turn, when the runtime has one. It is the only way a
         * caller can answer a provider's question: the event stream reports that one is waiting,
         * and this is the handle that can reply.
         *
         * Declared before [onEvent] so the trailing lambda a caller writes still binds to events.
         */
        onSession: (AgentId, AgentSession) -> Unit = { _, _ -> },
        onSessionClosed: (AgentId, AgentSession) -> Unit = { _, _ -> },
        onEvent: (AgentEvent) -> Unit = {},
    ): ConductorResult {
        require(!resumeProviderSession || protocol is Protocol.Ask) { "Provider continuation is supported only for Ask" }
        var counter = 0
        val usedIds = thread.events.mapTo(mutableSetOf()) { it.id }
        fun nextId(): EventId {
            repeat(usedIds.size + 1) {
                val candidate = idFactory(++counter)
                if (usedIds.add(candidate)) return candidate
            }
            throw ThreadIntegrityException("Event id factory cannot produce a unique id")
        }
        val promptEvent = ThreadEvent(
            id = nextId(),
            author = author,
            kind = EventKind.Prompt,
            text = prompt,
            parents = thread.heads().map { it.id },
            createdAtEpochMillis = clock(),
        )
        var document = thread.append(promptEvent)
        val added = mutableListOf(promptEvent)
        val checkpointMutex = Mutex()
        onCheckpoint(document)

        // Agents are resolved against the live document, not the caller's snapshot, so a planner
        // can introduce participants that later stages then use.
        fun spec(id: AgentId): AgentSpec =
            document.agent(id) ?: throw ThreadIntegrityException("Unknown agent: ${id.value}")

        fun roster(ids: List<AgentId>): List<AgentSpec> =
            if (ids.isEmpty()) document.participants else ids.map(::spec)

        suspend fun turn(
            agent: AgentSpec,
            task: String,
            kind: EventKind,
            visibility: Visibility,
            peers: List<ThreadEvent>,
            parents: List<EventId>,
            round: Int,
            snapshot: ThreadDocument,
        ): ThreadEvent {
            val runtime = runtimes[agent.runtime]
                ?: return ThreadEvent(
                    id = EventId("pending"),
                    author = Author.Agent(agent.id),
                    kind = EventKind.Failure,
                    text = "No runtime registered for ${agent.runtime.name}",
                    parents = parents,
                    round = round,
                    createdAtEpochMillis = clock(),
                )
            val previous = snapshot.events.dropLast(1).lastOrNull()
            val canResume = resumeProviderSession && previous?.author == Author.Agent(agent.id) && previous.kind != EventKind.Failure
            val resume = if (canResume) snapshot.providerSessions[agent.id.value]?.takeIf { it.runtime == agent.runtime } else null
            fun turnUsage(outcome: AgentOutcome): AgentUsage? {
                val baseline = snapshot.events.lastOrNull { it.session != null && it.session == outcome.session }?.reportedUsage
                return outcome.usage?.forTurn(baseline, freshSession = resume == null)
            }
            val compiled = compiler.compile(
                CompileRequest(
                    thread = snapshot,
                    agent = agent,
                    task = task,
                    visibility = if (resume != null) visibility.copy(includeHistory = false) else visibility,
                    peers = peers,
                    context = context,
                ),
            )
            val outcome = runtime.awaitSession(
                AgentRequest(agent = agent, prompt = compiled, workingDirectory = workingDirectory,
                    resume = resume, persistSession = resumeProviderSession),
                onSession = { session -> onSession(agent.id, session) },
                onClosed = { session -> onSessionClosed(agent.id, session) },
            ) { event ->
                if (event is AgentEvent.Started && event.session != null) {
                    checkpointMutex.withLock {
                        document = document.copy(
                            providerSessions = document.providerSessions + (agent.id.value to event.session),
                        )
                        onCheckpoint(document)
                    }
                }
                onEvent(if (event is AgentEvent.Finished) event.copy(outcome = event.outcome.copy(usage = turnUsage(event.outcome))) else event)
            }
            return ThreadEvent(
                id = EventId("pending"),
                author = Author.Agent(agent.id),
                kind = if (outcome.ok) kind else EventKind.Failure,
                text = if (outcome.ok) outcome.text else (outcome.failure ?: "Agent failed"),
                parents = parents,
                round = round,
                createdAtEpochMillis = clock(),
                session = outcome.session,
                usage = turnUsage(outcome),
                reportedUsage = outcome.usage,
            )
        }

        // Ids are assigned after a parallel round completes, so they follow roster order and the
        // document does not depend on which harness happened to finish first.
        suspend fun round(
            agents: List<AgentSpec>,
            task: String,
            kind: EventKind,
            visibility: Visibility,
            peers: List<ThreadEvent>,
            parents: List<EventId>,
            index: Int,
        ): List<ThreadEvent> {
            val snapshot = document
            val results = coroutineScope {
                agents.map { agent ->
                    async { turn(agent, task, kind, visibility, peers, parents, index, snapshot) }
                }.awaitAll()
            }
            val committed = results.map { result ->
                val identified = result.copy(id = nextId())
                document = document.append(identified)
                added += identified
                onCheckpoint(document)
                identified
            }
            return committed
        }

        val everything = setOf(
            EventKind.Proposal,
            EventKind.Critique,
            EventKind.Revision,
            EventKind.Synthesis,
        )

        suspend fun execute(active: Protocol, root: List<EventId>, allowPlanning: Boolean) {
            when (active) {
                is Protocol.Ask -> round(
                    agents = listOf(spec(active.agent)),
                    task = prompt,
                    kind = EventKind.Proposal,
                    visibility = Visibility.Shared,
                    peers = emptyList(),
                    parents = root,
                    index = 1,
                )

                is Protocol.All -> round(
                    agents = roster(active.agents),
                    task = prompt,
                    kind = EventKind.Proposal,
                    visibility = if (active.blind) Visibility.Blind else Visibility.Shared,
                    peers = emptyList(),
                    parents = root,
                    index = 1,
                )

                is Protocol.Council -> {
                    val agents = roster(active.agents)
                    val proposals = round(
                        agents = agents,
                        task = prompt,
                        kind = EventKind.Proposal,
                        visibility = Visibility.Blind,
                        peers = emptyList(),
                        parents = root,
                        index = 1,
                    )

                    val critiques = if (active.critique && agents.size > 1) {
                        round(
                            agents = agents,
                            task = CRITIQUE_INSTRUCTION,
                            kind = EventKind.Critique,
                            visibility = Visibility.Shared,
                            peers = proposals,
                            parents = proposals.map { it.id },
                            index = 2,
                        )
                    } else {
                        emptyList()
                    }

                    val synthesizer = active.synthesizer?.let(::spec) ?: agents.firstOrNull()
                    if (synthesizer != null && agents.size > 1) {
                        round(
                            agents = listOf(synthesizer),
                            task = "$prompt\n\n$SYNTHESIS_INSTRUCTION",
                            kind = EventKind.Synthesis,
                            visibility = Visibility.Shared.copy(peerKinds = everything),
                            peers = proposals + critiques,
                            parents = (critiques.ifEmpty { proposals }).map { it.id },
                            index = 3,
                        )
                    }
                }

                is Protocol.Pipeline -> {
                    var previous = root.mapNotNull(document::event)
                    active.stages.forEachIndexed { index, stage ->
                        previous = round(
                            agents = listOf(spec(stage.agent)),
                            task = stage.instruction,
                            kind = stage.kind,
                            visibility = if (stage.seesPeers) {
                                Visibility.Shared.copy(peerKinds = everything)
                            } else {
                                Visibility.Blind
                            },
                            peers = if (stage.seesPeers) previous else emptyList(),
                            parents = previous.map { it.id },
                            index = index + 1,
                        )
                    }
                }

                is Protocol.Planned -> {
                    if (!allowPlanning) {
                        throw ThreadIntegrityException("A planned protocol may not nest another")
                    }
                    val planned = round(
                        agents = listOf(spec(active.planner)),
                        task = planningInstruction(document.participants),
                        kind = EventKind.Note,
                        visibility = Visibility.Shared,
                        peers = emptyList(),
                        parents = root,
                        index = 0,
                    ).single()

                    val plan = extractJsonObject(planned.text)?.let { payload ->
                        runCatching {
                            ConductorJson.decodeFromString(TeamPlan.serializer(), payload)
                        }.getOrNull()
                    } ?: throw ThreadIntegrityException(
                        "Planner ${active.planner.value} did not return a usable TeamPlan",
                    )

                    val known = document.participants.mapTo(mutableSetOf()) { it.id }
                    document = document.copy(
                        participants = document.participants + plan.agents.filter { it.id !in known },
                    )
                    onCheckpoint(document)
                    execute(plan.protocol, listOf(planned.id), allowPlanning = false)
                }
            }
        }

        execute(protocol, listOf(promptEvent.id), allowPlanning = true)
        return ConductorResult(document, added)
    }
}
