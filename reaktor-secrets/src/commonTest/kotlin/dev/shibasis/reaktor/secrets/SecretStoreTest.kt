package dev.shibasis.reaktor.secrets

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SecretStoreTest {
    @Test
    fun `secret refs round trip to GCP resource names`() {
        val ref = SecretRef.gcp("project-a", "api-key", "7")

        assertEquals("projects/project-a/secrets/api-key/versions/7", ref.resourceName)
        assertEquals(ref, SecretRef.parse(ref.resourceName))
    }

    @Test
    fun `in memory store resolves latest versions`() = runTest {
        val store = InMemorySecretStore(defaultProjectId = "project-a")

        val first = store.addVersion("api-key", "one").getOrThrow()
        val second = store.addVersion("api-key", "two").getOrThrow()

        assertEquals("projects/project-a/secrets/api-key/versions/1", first.versionName)
        assertEquals("projects/project-a/secrets/api-key/versions/2", second.versionName)
        assertEquals("two", store.requireString("api-key"))
        assertEquals("one", store.requireString("api-key", version = "1"))
    }

    @Test
    fun `missing default project fails before lookup`() {
        val store = InMemorySecretStore()

        assertFailsWith<IllegalStateException> {
            store.ref("api-key")
        }
    }
}
