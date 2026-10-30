package dev.shibasis.reaktor.security

internal actual object SecurityEngineFactory {
    actual fun create(policy: EnginePolicy, suite: MlsCipherSuiteId): SecurityEngine = NativeSecurityEngine.create(policy, suite)
    actual fun restore(snapshot: ByteArray, policy: EnginePolicy): SecurityEngine = NativeSecurityEngine.restore(snapshot, policy)
    actual fun keyRing(encoded: ByteArray, policy: EnginePolicy): EngineKeyRing = NativeSecurityEngine.keyRing(encoded, policy)
    actual fun storageKey(): ByteArray = NativeSecurityEngine.storageKey()
    actual fun sealStorage(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray = NativeSecurityEngine.sealStorage(key, aad, plaintext)
    actual fun openStorage(key: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray = NativeSecurityEngine.openStorage(key, aad, ciphertext)
}
