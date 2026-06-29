package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.MintPatRequest
import dev.shibasis.reaktor.auth.runtime.AuthPat
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeConfig
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeGraph
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.consumes
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Locks the AuthRuntimeConfig security seams introduced to remove a silent prod fail-open and a
 * hidden env read from inside a graph node:
 *  - a missing signing key must fail fast unless ephemeral keys are explicitly allowed;
 *  - the PAT bootstrap bearer comes from config (set at the composition root), not System.getenv.
 */
class AuthRuntimeConfigSecurityTest {
    @BeforeTest
    fun setup() = AuthDbFixture.ensure()

    @Test
    fun missingSigningKeyFailsFastWhenEphemeralDisabled() {
        val error = assertFails {
            AuthRuntimeGraph.create(
                adapter = AuthDbFixture.adapter(),
                config = AuthRuntimeConfig(ecJwkJson = "", allowEphemeralSigningKey = false),
            )
        }
        val messages = generateSequence(error as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
        assertTrue(
            "ec-jwk" in messages || "ephemeral" in messages,
            "expected a signing-key fail-fast, got: $messages",
        )
    }

    @Test
    fun ephemeralSigningKeyAllowedForEmbeddedConstruction() {
        // The data-class default stays permissive so embedded/test construction keeps working.
        val runtime = AuthRuntimeGraph.create(AuthDbFixture.adapter())
        assertNotNull(runtime.jwt.signingKeys)
    }

    @Test
    fun patBootstrapTokenFromConfigAuthorizesFirstMint() = runBlocking {
        val runtime = AuthRuntimeGraph.create(
            adapter = AuthDbFixture.adapter(),
            config = AuthRuntimeConfig(patBootstrapToken = "boot-secret"),
        )
        val consumer = object : BasicNode(runtime) {
            val patPort by consumes<AuthPat>()
        }
        runtime.attach(consumer)
        runtime.autoWire()

        val authorized = consumer.patPort.suspended {
            mint(MintPatRequest(name = "bootstrap", headers = mutableMapOf("Authorization" to "Bearer boot-secret")))
        }
        assertEquals(StatusCode.OK, authorized.statusCode)

        val rejected = consumer.patPort.suspended {
            mint(MintPatRequest(name = "nope", headers = mutableMapOf("Authorization" to "Bearer wrong-secret")))
        }
        assertEquals(StatusCode.UNAUTHORIZED, rejected.statusCode)
    }
}
