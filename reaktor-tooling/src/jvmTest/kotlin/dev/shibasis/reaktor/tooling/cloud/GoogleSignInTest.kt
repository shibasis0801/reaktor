package dev.shibasis.reaktor.tooling.cloud

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class GoogleSignInTest {
    private fun home(block: (File) -> Unit) {
        val root = Files.createTempDirectory("google-sign-in-").toFile()
        try {
            val gcloud = File(root, ".config/gcloud").apply { mkdirs() }
            File(gcloud, "active_config").writeText("work\n")
            File(gcloud, "configurations").mkdirs()
            File(gcloud, "configurations/config_default").writeText("[core]\naccount = other@example.com\nproject = default-project\n")
            File(gcloud, "configurations/config_work").writeText("[auth]\naccount = wrong@example.com\n[core]\naccount = active@example.com\nproject = work-project\n")
            listOf("other@example.com", "active@example.com", "zeta@example.com").forEach { account ->
                File(gcloud, "legacy_credentials/$account").mkdirs()
                File(gcloud, "legacy_credentials/$account/adc.json").writeText("{}")
            }
            File(gcloud, "legacy_credentials/empty@example.com").mkdirs()
            block(root)
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun theActiveConfigurationNamesTheAccountAndProject() = home { root ->
        val signIn = GoogleSignIn(root, emptyMap())
        assertEquals("active@example.com", signIn.activeAccount())
        assertEquals("work-project", signIn.project())
        assertEquals(listOf("active@example.com", "other@example.com", "zeta@example.com"), signIn.accounts())
        assertEquals("active@example.com", signIn.credentialFile()?.parentFile?.name)
        assertTrue(signIn.signedIn())
    }

    @Test fun environmentOverridesWinOverTheStoredConfiguration() = home { root ->
        val signIn = GoogleSignIn(root, mapOf("CLOUDSDK_ACTIVE_CONFIG_NAME" to "default", "CLOUDSDK_CORE_PROJECT" to "override-project"))
        assertEquals("other@example.com", signIn.activeAccount())
        assertEquals("override-project", signIn.project())
        assertEquals("other@example.com", signIn.accounts().first())
    }

    @Test fun applicationDefaultCredentialsComeFirstWhenNoAccountIsNamed() = home { root ->
        val adc = File(root, ".config/gcloud/application_default_credentials.json").apply { writeText("{}") }
        val signIn = GoogleSignIn(root, emptyMap())
        assertEquals(adc, signIn.credentialFile())
        assertEquals("application default credentials", signIn.describe(null))
        assertEquals("zeta@example.com", signIn.credentialFile("zeta@example.com")?.parentFile?.name)
    }

    @Test fun anAccountWithoutStoredCredentialsIsRefused() = home { root ->
        val signIn = GoogleSignIn(root, emptyMap())
        assertNull(signIn.credentialFile("empty@example.com"))
        assertNull(signIn.credentialFile("../../etc"))
        assertFailsWith<GoogleSignInRequired> { runBlocking { signIn.token("missing@example.com") } }
    }

    @Test fun noStoredSignInAsksForOne() {
        val root = Files.createTempDirectory("google-sign-in-empty-").toFile()
        try {
            val signIn = GoogleSignIn(root, emptyMap())
            assertFalse(signIn.signedIn())
            assertNull(signIn.project())
            assertContains(assertFailsWith<GoogleSignInRequired> { runBlocking { signIn.token() } }.message.orEmpty(), "gcloud auth login")
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
