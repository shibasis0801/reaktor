package dev.shibasis.reaktor.io

import dev.shibasis.reaktor.io.network.redactSecrets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What request logging prints about a URL, with the credentials taken out. Runs on JS as well. */
class RedactSecretsTest {
    @Test
    fun aTokenInASocketAddressIsNotLogged() {
        val line = "REQUEST: ws://10.0.2.2:8787/parties/cairn-room/29b8?_pk=c591&token=58f5a8b2245e1e42e44ce8d19ee9ddc1"
        val logged = redactSecrets(line)

        assertTrue("58f5a8b2" !in logged, logged)
        assertEquals("REQUEST: ws://10.0.2.2:8787/parties/cairn-room/29b8?_pk=c591&token=REDACTED", logged)
    }

    @Test
    fun everyCredentialShapedParameterIsCovered() {
        for (name in listOf("token", "access_token", "refresh_token", "id_token", "api_key", "apiKey", "key", "secret", "password", "sig", "signature", "TOKEN")) {
            val logged = redactSecrets("https://example.com/x?$name=hunter2&page=3")
            assertEquals("https://example.com/x?$name=REDACTED&page=3", logged, name)
        }
    }

    @Test
    fun ordinaryParametersAreLeftAlone() {
        val line = "REQUEST: https://example.com/search?q=token&page=2&tokens=7&monkey=1"
        assertEquals(line, redactSecrets(line))
    }

    @Test
    fun theValueStopsWhereTheParameterDoes() {
        assertEquals(
            "FROM: https://h/p?token=REDACTED#frag and ?key=REDACTED then words",
            redactSecrets("FROM: https://h/p?token=abc#frag and ?key=xyz then words"),
        )
    }
}
