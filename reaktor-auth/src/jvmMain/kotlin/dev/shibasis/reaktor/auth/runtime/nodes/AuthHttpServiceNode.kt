package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.api.AuthService
import dev.shibasis.reaktor.auth.runtime.AuthAccount
import dev.shibasis.reaktor.auth.runtime.AuthLogin
import dev.shibasis.reaktor.auth.runtime.AuthPat
import dev.shibasis.reaktor.auth.runtime.AuthSessions
import dev.shibasis.reaktor.auth.runtime.AuthTokenGrants
import dev.shibasis.reaktor.auth.runtime.ports.AuthAuthority
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.consumes

class AuthHttpServiceNode(graph: Graph) : BasicNode(graph) {
    val loginPort by consumes<AuthLogin>()
    val patPort by consumes<AuthPat>()
    val tokenGrantsPort by consumes<AuthTokenGrants>()
    val sessionsPort by consumes<AuthSessions>()
    val accountPort by consumes<AuthAccount>()
    val authorityPort by consumes<AuthAuthority>()

    val service: AuthService = object : AuthService() {
        override val authorityGrants = super.authorityGrants.handle { request -> authorityPort.suspended { grants(request) } }
        override val authorityResolve = super.authorityResolve.handle { request -> authorityPort.suspended { resolve(request) } }
        override val anonymous = super.anonymous.handle { request -> loginPort.suspended { anonymous(request) } }
        override val login = super.login.handle { request -> loginPort.suspended { login(request) } }
        override val mintPat = super.mintPat.handle { request -> patPort.suspended { mint(request) } }
        override val verifyPat = super.verifyPat.handle { request -> patPort.suspended { verify(request) } }
        override val token = super.token.handle { request -> tokenGrantsPort.suspended { issue(request) } }
        override val sessionRefresh = super.sessionRefresh.handle { request -> sessionsPort.suspended { refresh(request) } }
        override val sessionLogout = super.sessionLogout.handle { request -> sessionsPort.suspended { logout(request) } }
        override val sessionMe = super.sessionMe.handle { request -> sessionsPort.suspended { me(request) } }
        override val sessionLogoutAll = super.sessionLogoutAll.handle { request -> sessionsPort.suspended { logoutAll(request) } }
        override val accountDeactivate = super.accountDeactivate.handle { request -> accountPort.suspended { deactivate(request) } }
    }
}
