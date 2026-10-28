package dev.shibasis.reaktor.auth.graph

import dev.shibasis.reaktor.auth.fga.DirectType
import dev.shibasis.reaktor.auth.fga.FgaRelation
import dev.shibasis.reaktor.auth.fga.FgaSchema
import dev.shibasis.reaktor.auth.fga.FgaType

object CodeAccessModel : FgaSchema() {
    object User : FgaType(CodeAccess.User)

    object ServiceAccount : FgaType(CodeAccess.ServiceAccount)

    object Agent : FgaType(CodeAccess.Agent)

    private val members: List<DirectType> get() = listOf(User, ServiceAccount, Agent, Role.assignee)

    private val callers: List<DirectType>
        get() = listOf(User, User.wildcard, ServiceAccount, ServiceAccount.wildcard, Agent, Agent.wildcard, Role.assignee, Permission.holder)

    object Role : FgaType(CodeAccess.Role) {
        val assignee: FgaRelation by relation { direct(members) }
    }

    object Permission : FgaType(CodeAccess.Permission) {
        val holder: FgaRelation by relation { direct(members) }
    }

    object Graph : FgaType(CodeAccess.Graph) {
        val parent: FgaRelation by relation { direct(Graph) }
        val owner: FgaRelation by relation { direct(User, Role.assignee) or (owner from parent) }
        val operator: FgaRelation by relation { direct(members) or owner or (operator from parent) }
        val viewer: FgaRelation by relation { direct(members) or operator or (viewer from parent) }
    }

    object Service : FgaType(CodeAccess.Service) {
        val graph: FgaRelation by relation { direct(Graph) }
        val owner: FgaRelation by relation { direct(User, Role.assignee) or (Graph.owner from graph) }
        val caller: FgaRelation by relation { direct(callers) or owner or (Graph.operator from graph) }
        val viewer: FgaRelation by relation { direct(members) or caller or (Graph.viewer from graph) }
    }

    object Operation : FgaType(CodeAccess.Operation) {
        val service: FgaRelation by relation { direct(Service) }
        val invoker: FgaRelation by relation { direct(callers) }
        val blocked: FgaRelation by relation { direct(members) }
        val canInvoke: FgaRelation by relation { (invoker or (Service.caller from service)) butNot blocked }
    }

    object Node : FgaType(CodeAccess.Node) {
        val graph: FgaRelation by relation { direct(Graph) }
        val owner: FgaRelation by relation { direct(User, Role.assignee) or (Graph.owner from graph) }
        val caller: FgaRelation by relation { direct(callers) or owner or (Graph.operator from graph) }
    }

    object Port : FgaType(CodeAccess.Port) {
        val node: FgaRelation by relation { direct(Node) }
        val invoker: FgaRelation by relation { direct(callers) }
        val blocked: FgaRelation by relation { direct(members) }
        val canInvoke: FgaRelation by relation { (invoker or (Node.caller from node)) butNot blocked }
    }

    override val types get() = listOf(User, ServiceAccount, Agent, Role, Permission, Graph, Service, Operation, Node, Port)
}
