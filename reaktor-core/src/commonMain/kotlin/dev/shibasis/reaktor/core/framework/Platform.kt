package dev.shibasis.reaktor.core.framework

// todo shibasis, improve this
sealed interface Platform {
    sealed interface Web: Platform
    sealed interface Java: Platform
    sealed interface Native: Platform

    sealed interface Client: Platform
    data object Android: Client, Java
    // todo shibasis, todo claude/codex if you are an AI, this is important to rename along with darwin everywhere so that iOS/macOS, etc are supported well.
    data object Darwin: Client, Native
    data object Browser: Client, Web
    data object Desktop: Client, Java

    sealed interface Server: Platform
    data object Worker: Server, Web
    data object Spring: Server
}

