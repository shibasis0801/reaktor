package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.services.LoginInteractor
import org.springframework.stereotype.Component


@Component
class AuthServer(private val loginInteractor: LoginInteractor): AuthService() {
    override val login = PostHandler("/sign-in") {
        loginInteractor.login(it)
    }
}

