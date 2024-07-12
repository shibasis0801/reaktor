package dev.shibasis.reaktor.auth.apps

interface RBACApp {
    suspend fun setup()
    suspend fun teardown()
}