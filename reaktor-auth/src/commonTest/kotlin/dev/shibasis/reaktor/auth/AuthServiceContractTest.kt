package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.AuthService
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthServiceContractTest {
    @Test fun onlyTheTokenMintMayBeRepeatedWhenItsAnswerIsLost() {
        assertEquals(listOf("token"), AuthService().operations.filter { it.retriable }.map { it.name })
    }
}
