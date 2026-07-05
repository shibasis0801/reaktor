package dev.shibasis.reaktor.auth.api

class AuthServer(
    private val graphService: AuthService,
) : AuthService() {
    override val anonymous = graphService.anonymous
    override val login = graphService.login
    override val token = graphService.token
    override val mintPat = graphService.mintPat
    override val verifyPat = graphService.verifyPat
    override val sessionRefresh = graphService.sessionRefresh
    override val sessionLogout = graphService.sessionLogout
    override val sessionMe = graphService.sessionMe
    override val sessionLogoutAll = graphService.sessionLogoutAll

    init {
        handlers += listOf(
            anonymous,
            login,
            token,
            mintPat,
            verifyPat,
            sessionRefresh,
            sessionLogout,
            sessionMe,
            sessionLogoutAll,
        )
    }
}
