package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AgentIdentityTest {
    private val person = BoardActor(BoardActorKind.Person, "Owner")
    private val falcon = BoardActor(BoardActorKind.Agent, "codex", "codex:falcon")
    private val other = BoardActor(BoardActorKind.Agent, "codex", "codex:other")

    @Test fun nativeBindingsSurviveRestartAndKeepTheProfileRevisionOfEachWrite() = fixture { store, reload ->
        val fresh = store.identity(falcon)
        assertEquals("agent:codex:falcon", fresh.actor.agent)
        assertNull(fresh.profile.model)
        assertNull(fresh.profile.effort)
        val profile = store.save(AgentProfile("falcon", "Falcon", RuntimeKind.Codex, model = "configured-model", effort = "xhigh"), person)
        val bound = store.bind(falcon, profile.id, falcon, fresh.bindingRevision)
        assertEquals("Falcon", bound.actor.name)
        assertEquals(profile.id, bound.actor.profile)
        assertEquals(profile.revision, bound.actor.profileRevision)
        assertEquals(bound, reload().identity(falcon))
        val edited = store.save(profile.copy(name = "Falcon Two"), person, profile.revision)
        assertEquals(edited.revision, store.identity(falcon).actor.profileRevision)
        assertEquals("Falcon Two", store.identity(falcon).actor.name)
        assertEquals(bound.actor, store.identity(bound.actor).actor)
        assertEquals("Codex", store.identity(other).actor.name)
    }

    @Test fun agentsCannotRebindAnotherIdentityOrUseAStaleBindingRevision() = fixture { store, _ ->
        store.save(AgentProfile("falcon", "Falcon", RuntimeKind.Codex), person)
        store.save(AgentProfile("tower", "Tower", RuntimeKind.ClaudeCode), person)
        val current = store.identity(falcon)
        assertFailsWith<IllegalArgumentException> { store.bind(falcon, "falcon", other, current.bindingRevision) }
        assertFailsWith<IllegalArgumentException> { store.bind(falcon, "tower", falcon, current.bindingRevision) }
        store.bind(falcon, "falcon", person, current.bindingRevision)
        assertFailsWith<IllegalArgumentException> { store.bind(falcon, "falcon", falcon, current.bindingRevision) }
        assertFailsWith<IllegalArgumentException> { store.identity(falcon.copy(session = "codex:../outside")) }
        assertFailsWith<IllegalArgumentException> { store.identity(falcon.copy(session = "hangar:run-1")) }
        assertEquals("Falcon", store.identity(falcon).actor.name)
    }

    private fun fixture(test: (AgentProfileStore, () -> AgentProfileStore) -> Unit) {
        val directory = Files.createTempDirectory("agent-identity-")
        try { test(AgentProfileStore(directory) { 42L }) { AgentProfileStore(directory) } }
        finally { Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
