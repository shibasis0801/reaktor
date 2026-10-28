package dev.shibasis.reaktor.auth.graph

import dev.shibasis.reaktor.auth.fga.Fga
import dev.shibasis.reaktor.auth.fga.FgaDsl
import dev.shibasis.reaktor.auth.fga.FgaJson
import dev.shibasis.reaktor.auth.fga.ObjectRef
import dev.shibasis.reaktor.auth.fga.check
import dev.shibasis.reaktor.auth.graph.CodeAccessModel.Operation
import dev.shibasis.reaktor.auth.graph.CodeAccessModel.Role
import dev.shibasis.reaktor.auth.graph.CodeAccessModel.Service
import dev.shibasis.reaktor.auth.graph.CodeAccessModel.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeAccessModelTest {
    private val starter = """
        model
          schema 1.1

        type user

        type service_account

        type agent

        type role
          relations
            define assignee: [user, service_account, agent, role#assignee]

        type permission
          relations
            define holder: [user, service_account, agent, role#assignee]

        type graph
          relations
            define parent: [graph]
            define owner: [user, role#assignee] or owner from parent
            define operator: [user, service_account, agent, role#assignee] or owner or operator from parent
            define viewer: [user, service_account, agent, role#assignee] or operator or viewer from parent

        type service
          relations
            define graph: [graph]
            define owner: [user, role#assignee] or owner from graph
            define caller: [user, user:*, service_account, service_account:*, agent, agent:*, role#assignee, permission#holder] or owner or operator from graph
            define viewer: [user, service_account, agent, role#assignee] or caller or viewer from graph

        type operation
          relations
            define service: [service]
            define invoker: [user, user:*, service_account, service_account:*, agent, agent:*, role#assignee, permission#holder]
            define blocked: [user, service_account, agent, role#assignee]
            define can_invoke: (invoker or caller from service) but not blocked

        type node
          relations
            define graph: [graph]
            define owner: [user, role#assignee] or owner from graph
            define caller: [user, user:*, service_account, service_account:*, agent, agent:*, role#assignee, permission#holder] or owner or operator from graph

        type port
          relations
            define node: [node]
            define invoker: [user, user:*, service_account, service_account:*, agent, agent:*, role#assignee, permission#holder]
            define blocked: [user, service_account, agent, role#assignee]
            define can_invoke: (invoker or caller from node) but not blocked
    """.trimIndent() + "\n"

    @Test
    fun theKotlinModelPrintsTheStarterOpenFgaModel() {
        assertEquals(starter, CodeAccessModel.dsl())
        assertEquals(starter, CodeAccess.starter)
        assertEquals(FgaDsl.parse(starter).model, CodeAccessModel.model)
        assertEquals(emptyList(), CodeAccessModel.issues)
    }

    @Test
    fun theKotlinModelRoundTripsThroughOpenFgaJson() {
        assertEquals(CodeAccessModel.model, FgaJson.decode(CodeAccessModel.json()).getOrThrow())
    }

    @Test
    fun typedHandlesWriteTuplesAndChecks() {
        assertEquals(ObjectRef("user", "ada"), User("ada"))
        assertEquals("role:member#assignee", Role.assignee("member").toString())
        val tuples = listOf(
            Operation.service.tuple("people.person", Service.subject("people")),
            Service.caller.tuple("people", Role.assignee("member")),
            Role.assignee.tuple("member", User.subject("ada")),
        )
        assertEquals("service:people#caller@role:member#assignee", tuples[1].toString())
        val fga = Fga(CodeAccessModel.model, tuples)
        assertTrue(fga.check(User.subject("ada"), Operation.canInvoke, "people.person").allowed)
        assertFalse(fga.check(User.subject("bob"), Operation.canInvoke, "people.person").allowed)
        assertEquals(CodeAccess.Invoke, Operation.canInvoke.name)
    }
}
