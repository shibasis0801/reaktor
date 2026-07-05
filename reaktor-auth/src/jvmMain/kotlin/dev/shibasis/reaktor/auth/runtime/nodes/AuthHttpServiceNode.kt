package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.api.AuthService
import dev.shibasis.reaktor.auth.api.AnonymousAuthRequest
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.auth.api.LoginResponse
import dev.shibasis.reaktor.auth.api.LogoutAllRequest
import dev.shibasis.reaktor.auth.api.LogoutAllResponse
import dev.shibasis.reaktor.auth.api.LogoutRequest
import dev.shibasis.reaktor.auth.api.LogoutResponse
import dev.shibasis.reaktor.auth.api.MeRequest
import dev.shibasis.reaktor.auth.api.MeResponse
import dev.shibasis.reaktor.auth.api.MintPatRequest
import dev.shibasis.reaktor.auth.api.MintPatResponse
import dev.shibasis.reaktor.auth.api.RefreshRequest
import dev.shibasis.reaktor.auth.api.RefreshResponse
import dev.shibasis.reaktor.auth.api.TokenRequest
import dev.shibasis.reaktor.auth.api.TokenResponse
import dev.shibasis.reaktor.auth.api.VerifyPatRequest
import dev.shibasis.reaktor.auth.api.VerifyPatResponse
import dev.shibasis.reaktor.auth.runtime.AuthHttpService
import dev.shibasis.reaktor.auth.runtime.AuthLogin
import dev.shibasis.reaktor.auth.runtime.AuthPat
import dev.shibasis.reaktor.auth.runtime.AuthSessions
import dev.shibasis.reaktor.auth.runtime.AuthTokenGrants
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.service.PostHandler
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides

class AuthHttpServiceNode(graph: Graph) : BasicNode(graph), AuthHttpService {
    val loginPort by consumes<AuthLogin>()
    val patPort by consumes<AuthPat>()
    val tokenGrantsPort by consumes<AuthTokenGrants>()
    val sessionsPort by consumes<AuthSessions>()
    val httpServicePort by provides<AuthHttpService>(this)

    override val service: AuthService = object : AuthService() {
        override val anonymous = PostHandler<AnonymousAuthRequest, LoginResponse>("/anonymous") {
            loginPort.suspended { anonymous(it) }
        }

        override val login = PostHandler<LoginRequest, LoginResponse>("/sign-in") {
            loginPort.suspended { login(it) }
        }

        override val mintPat = PostHandler<MintPatRequest, MintPatResponse>("/pat/mint") {
            patPort.suspended { mint(it) }
        }

        override val verifyPat = PostHandler<VerifyPatRequest, VerifyPatResponse>("/pat/verify") {
            patPort.suspended { verify(it) }
        }

        override val token = PostHandler<TokenRequest, TokenResponse>("/token") {
            tokenGrantsPort.suspended { issue(it) }
        }

        override val sessionRefresh = PostHandler<RefreshRequest, RefreshResponse>("/session/refresh") {
            sessionsPort.suspended { refresh(it) }
        }

        override val sessionLogout = PostHandler<LogoutRequest, LogoutResponse>("/session/logout") {
            sessionsPort.suspended { logout(it) }
        }

        override val sessionMe = PostHandler<MeRequest, MeResponse>("/session/me") {
            sessionsPort.suspended { me(it) }
        }

        override val sessionLogoutAll = PostHandler<LogoutAllRequest, LogoutAllResponse>("/session/logout-all") {
            sessionsPort.suspended { logoutAll(it) }
        }
    }
}
