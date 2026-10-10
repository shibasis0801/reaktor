package dev.shibasis.reaktor.security

import kotlinx.coroutines.*
import kotlin.test.*

class KeychainInteractionTest {
    @Test fun explicitInteractionFollowsDispatcherChangesAndDoesNotEnterBackgroundWork() = runBlocking {
        assertFalse(MacKeychainCredentialStore.userInteractionAllowed)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val interactive = launch {
            MacKeychainCredentialStore.withUserInteraction {
                withContext(Dispatchers.IO) {
                    assertTrue(MacKeychainCredentialStore.userInteractionAllowed)
                    entered.complete(Unit)
                    release.await()
                }
            }
            assertFalse(MacKeychainCredentialStore.userInteractionAllowed)
        }
        entered.await()
        withContext(Dispatchers.IO) { assertFalse(MacKeychainCredentialStore.userInteractionAllowed) }
        release.complete(Unit)
        interactive.join()
        assertFalse(MacKeychainCredentialStore.userInteractionAllowed)
    }

    @Test fun cancelledInteractionRestoresBackgroundPolicy() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val interactive = launch {
            try {
                MacKeychainCredentialStore.withUserInteraction {
                    assertTrue(MacKeychainCredentialStore.userInteractionAllowed)
                    entered.complete(Unit)
                    awaitCancellation()
                }
            } finally { assertFalse(MacKeychainCredentialStore.userInteractionAllowed) }
        }
        entered.await()
        interactive.cancelAndJoin()
        assertFalse(MacKeychainCredentialStore.userInteractionAllowed)
    }
}
