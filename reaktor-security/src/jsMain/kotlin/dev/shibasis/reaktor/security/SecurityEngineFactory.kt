package dev.shibasis.reaktor.security

internal actual object SecurityEngineFactory {
    private fun unavailable(): Nothing = throw UnsupportedOperationException("The WebAssembly security engine is unavailable in this build")
    actual fun create(policy: EnginePolicy, suite: MlsCipherSuiteId): SecurityEngine = unavailable()
    actual fun restore(snapshot: ByteArray, policy: EnginePolicy): SecurityEngine = unavailable()
    actual fun keyRing(encoded: ByteArray, policy: EnginePolicy): EngineKeyRing = unavailable()
    actual fun storageKey(): ByteArray = unavailable()
    actual fun sealStorage(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray = unavailable()
    actual fun openStorage(key: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray = unavailable()
}
