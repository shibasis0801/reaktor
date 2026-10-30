package dev.shibasis.reaktor.security

import kotlin.test.*

class NativeMembershipLifecycleTest {
    @Test fun anUnregisteredIdentityRestoresWithoutReplacingItsSignatureKey() {
        val authority = SecurityTestCredentials()
        NativeSecurityEngine.create(authority.policy).use { engine ->
            val snapshot = engine.snapshot()
            try {
                NativeSecurityEngine.restore(snapshot, authority.policy).use { restored ->
                    assertContentEquals(engine.publicKey(), restored.publicKey())
                    restored.installCredential(authority.credential("alice", DeviceRegistration("alice-phone", restored.publicKey(), 3,
                        restored.proveDevice("alice", "alice-phone"))))
                    assertTrue(restored.keyPackage().isNotEmpty())
                }
            } finally { snapshot.fill(0) }
        }
    }

    @Test fun pendingBatchRemovalRestoresAndOfflineCatchUpUsesEachAcceptedRoster() {
        val authority = SecurityTestCredentials()
        authority.device("alice").use { alice -> authority.device("bob").use { bob ->
            authority.device("carol").use { carol -> authority.device("dana").use { dana ->
                val chat = "membership-history"
                val original = setOf("alice", "bob", "carol", "dana")
                listOf(alice, bob, carol, dana).forEach { it.setMembers(chat, original) }
                val created = alice.prepareCreate(chat, listOf(bob.keyPackage(), carol.keyPackage(), dana.keyPackage()))
                alice.accept(chat, created.commit)
                listOf(bob, carol, dana).forEach { it.join(chat, created.welcome) }
                val envelope = "message".encodeToByteArray()
                val old = alice.seal(chat, envelope, "before removals".encodeToByteArray())
                val current = setOf("alice", "bob")
                alice.setMembers(chat, current)
                assertFailsWith<NativeSecurityException> { alice.prepareRemove(chat, 2) }
                val removed = alice.prepareMembership(chat, emptyList(), listOf(2, 3))
                val snapshot = alice.snapshot()
                try {
                    NativeSecurityEngine.restore(snapshot, authority.policy).use { restarted ->
                        assertEquals(removed.epoch, restarted.pending(chat).epoch)
                        assertFailsWith<NativeSecurityException> { restarted.seal(chat, envelope, byteArrayOf(1)) }
                        restarted.accept(chat, removed.commit)
                        assertEquals(2, restarted.roster(chat).size)
                        bob.setMembers(chat, current)
                        assertTrue(bob.process(chat, removed.commit))
                        assertContentEquals("before removals".encodeToByteArray(), bob.open(chat, envelope, old).plaintext)
                        val fresh = restarted.seal(chat, envelope, "after removals".encodeToByteArray())
                        assertContentEquals("after removals".encodeToByteArray(), bob.open(chat, envelope, fresh).plaintext)
                        assertFailsWith<NativeSecurityException> { carol.open(chat, envelope, fresh) }
                        assertFailsWith<NativeSecurityException> { dana.open(chat, envelope, fresh) }
                    }
                } finally { snapshot.fill(0) }
            } }
        } }
    }
}

