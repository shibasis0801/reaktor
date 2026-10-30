package dev.shibasis.reaktor.security

internal class NativeSecurityException(val status: Int) : IllegalStateException("Native security operation failed ($status)")

internal data class EnginePolicy(val tenantId: String, val appId: String, val authKeys: Map<String, ByteArray>)
internal data class EngineCommit(val welcome: ByteArray, val commit: ByteArray, val groupInfo: ByteArray, val epoch: Long)
internal data class EngineSealed(val header: ByteArray, val commitment: ByteArray, val ciphertext: ByteArray)
internal data class EngineOpened(val plaintext: ByteArray, val frankingKey: ByteArray, val credential: ByteArray, val authenticated: Boolean, val duplicate: Boolean)

internal interface SecurityEngine : AutoCloseable {
    fun snapshot(): ByteArray
    fun exportKeyRing(): ByteArray
    fun publicKey(): ByteArray
    fun proveDevice(user: String, device: String): ByteArray
    fun installCredential(credential: ByteArray)
    fun keyPackage(): ByteArray
    fun lastResortKeyPackage(): ByteArray
    fun setMembers(id: String, users: Set<String>)
    fun roster(id: String): List<ByteArray>
    fun pending(id: String): EngineCommit
    fun prepareCreate(id: String, packages: List<ByteArray>): EngineCommit
    fun prepareAdd(id: String, packages: List<ByteArray>): EngineCommit
    fun prepareRemove(id: String, index: Int): EngineCommit
    fun prepareMembership(id: String, packages: List<ByteArray>, removals: List<Int>): EngineCommit
    fun prepareRotate(id: String): EngineCommit
    fun prepareExternalJoin(id: String, info: ByteArray): EngineCommit
    fun accept(id: String, commit: ByteArray): Long
    fun discard(id: String)
    fun epoch(id: String): Long
    fun join(id: String, welcome: ByteArray)
    fun process(id: String, commit: ByteArray): Boolean
    fun seal(id: String, envelope: ByteArray, plaintext: ByteArray, circleGame: Boolean = false, anonymous: Boolean = false): EngineSealed
    fun open(id: String, envelope: ByteArray, sealed: EngineSealed, circleGame: Boolean = false): EngineOpened
}

internal interface EngineKeyRing : AutoCloseable {
    fun open(id: String, envelope: ByteArray, sealed: EngineSealed, circleGame: Boolean = false): EngineOpened
}

internal expect object SecurityEngineFactory {
    fun create(policy: EnginePolicy, suite: MlsCipherSuiteId = MlsCipherSuiteId.MLS_128_DHKEMX25519_CHACHA20POLY1305_SHA256_Ed25519): SecurityEngine
    fun restore(snapshot: ByteArray, policy: EnginePolicy): SecurityEngine
    fun keyRing(encoded: ByteArray, policy: EnginePolicy): EngineKeyRing
    fun storageKey(): ByteArray
    fun sealStorage(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray
    fun openStorage(key: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray
}
