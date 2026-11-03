package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path

class AgentTeamStore(directory: Path, private val identity: (BoardActor) -> BoardActor = { it },
    private val clock: () -> Long = System::currentTimeMillis) {
    private val file = directory.resolve("team.json")
    private var team: AgentTeam
    private val changed: MutableStateFlow<Long>
    val changes: StateFlow<Long>

    init {
        privateDirectory(directory)
        team = if (Files.exists(file)) ConductorJson.decodeFromString(AgentTeam.serializer(), Files.readString(file)) else AgentTeam()
        changed = MutableStateFlow(team.revision)
        changes = changed.asStateFlow()
    }

    @Synchronized fun team(): AgentTeam = team

    @Synchronized fun read(since: Long? = null, reader: BoardActor? = null): TeamRead =
        TeamRead(team, since?.let { team.news(it, reader?.let(identity)) }.orEmpty())

    @Synchronized fun write(patch: TeamPatch, by: BoardActor, saved: (String) -> Boolean = { true }, busy: (String) -> String? = { null }): TeamApplied {
        val applied = TeamRules.apply(team, patch, identity(by), clock(), saved, busy)
        if (applied.team != team) save(applied.team)
        return applied
    }

    private fun save(next: AgentTeam) {
        val text = ConductorJson.encodeToString(AgentTeam.serializer(), next)
        require(text.length <= MaxChars) { "A team is at most ${MaxChars / 1024} KB" }
        atomicWrite(file, text)
        team = next
        changed.value = next.revision
    }

    companion object {
        const val MaxChars = 256 * 1024
    }
}
