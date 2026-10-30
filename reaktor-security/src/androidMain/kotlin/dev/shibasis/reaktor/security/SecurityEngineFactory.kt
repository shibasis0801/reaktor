package dev.shibasis.reaktor.security

internal actual object SecurityEngineFactory {
    actual fun create(policy: EnginePolicy, suite: MlsCipherSuiteId): SecurityEngine =
        AndroidSecurityEngine(createHandle(policy, suite.code.toInt(), null))
    actual fun restore(snapshot: ByteArray, policy: EnginePolicy): SecurityEngine =
        AndroidSecurityEngine(createHandle(policy, 0, snapshot))
    actual fun keyRing(encoded: ByteArray, policy: EnginePolicy): EngineKeyRing {
        val keys = policy.authKeys.entries.toList()
        require(keys.size in 1..8)
        return AndroidKeyRing(ReaktorSecurityNative.keyRing(policy.tenantId.bytes(), policy.appId.bytes(),
            keys.map { it.key.bytes() }.toTypedArray(), keys.map { it.value }.toTypedArray(), encoded))
    }
    actual fun storageKey(): ByteArray = ReaktorSecurityNative.storage(0, byteArrayOf(), byteArrayOf(), byteArrayOf())
    actual fun sealStorage(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray = ReaktorSecurityNative.storage(1, key, aad, plaintext)
    actual fun openStorage(key: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray = ReaktorSecurityNative.storage(2, key, aad, ciphertext)

    private fun createHandle(policy: EnginePolicy, suite: Int, snapshot: ByteArray?): Long {
        val keys = policy.authKeys.entries.toList()
        require(keys.size in 1..8)
        return ReaktorSecurityNative.create(policy.tenantId.bytes(), policy.appId.bytes(),
            keys.map { it.key.bytes() }.toTypedArray(), keys.map { it.value }.toTypedArray(), suite, snapshot)
    }
}

private fun String.bytes() = encodeToByteArray()
private fun Array<ByteArray>.commit(): EngineCommit {
    check(size == 4 && this[3].size == 8)
    val epoch = this[3].fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
    check(epoch >= 0)
    return EngineCommit(this[0], this[1], this[2], epoch)
}
private fun Array<ByteArray>.opened(): EngineOpened {
    check(size == 4 && this[3].size == 2)
    return EngineOpened(this[0], this[1], this[2], this[3][0].toInt() != 0, this[3][1].toInt() != 0)
}

private class AndroidSecurityEngine(private var handle: Long) : SecurityEngine {
    private fun engine() = handle.also { check(it != 0L) { "Security engine is closed" } }
    @Synchronized override fun close() { if (handle != 0L) ReaktorSecurityNative.destroy(handle, false); handle = 0 }
    @Synchronized override fun snapshot() = ReaktorSecurityNative.bytes(engine(), 0, byteArrayOf(), byteArrayOf())
    @Synchronized override fun exportKeyRing() = ReaktorSecurityNative.bytes(engine(), 1, byteArrayOf(), byteArrayOf())
    @Synchronized override fun publicKey() = ReaktorSecurityNative.bytes(engine(), 2, byteArrayOf(), byteArrayOf())
    @Synchronized override fun keyPackage() = ReaktorSecurityNative.bytes(engine(), 3, byteArrayOf(), byteArrayOf())
    @Synchronized override fun lastResortKeyPackage() = ReaktorSecurityNative.bytes(engine(), 4, byteArrayOf(), byteArrayOf())
    @Synchronized override fun proveDevice(user: String, device: String) = ReaktorSecurityNative.bytes(engine(), 5, user.bytes(), device.bytes())
    @Synchronized override fun installCredential(credential: ByteArray) { ReaktorSecurityNative.action(engine(), 0, byteArrayOf(), credential) }
    @Synchronized override fun setMembers(id: String, users: Set<String>) = ReaktorSecurityNative.members(engine(), id.bytes(), users.map(String::bytes).toTypedArray())
    @Synchronized override fun roster(id: String) = ReaktorSecurityNative.roster(engine(), id.bytes()).toList()
    @Synchronized override fun pending(id: String) = prepare(id, 0)
    @Synchronized override fun prepareCreate(id: String, packages: List<ByteArray>) = prepare(id, 1, packages)
    @Synchronized override fun prepareAdd(id: String, packages: List<ByteArray>) = prepare(id, 2, packages)
    @Synchronized override fun prepareRemove(id: String, index: Int) = prepare(id, 3, index = index)
    @Synchronized override fun prepareMembership(id: String, packages: List<ByteArray>, removals: List<Int>) = prepare(id, 6, packages, info = encodeRosterIndices(removals))
    @Synchronized override fun prepareRotate(id: String) = prepare(id, 4)
    @Synchronized override fun prepareExternalJoin(id: String, info: ByteArray) = prepare(id, 5, info = info)
    private fun prepare(id: String, operation: Int, packages: List<ByteArray> = emptyList(), index: Int = 0, info: ByteArray = byteArrayOf()): EngineCommit =
        ReaktorSecurityNative.prepare(engine(), operation, id.bytes(), packages.toTypedArray(), index, info).commit()
    @Synchronized override fun accept(id: String, commit: ByteArray) = ReaktorSecurityNative.action(engine(), 3, id.bytes(), commit)
    @Synchronized override fun discard(id: String) { ReaktorSecurityNative.action(engine(), 1, id.bytes(), byteArrayOf()) }
    @Synchronized override fun epoch(id: String) = ReaktorSecurityNative.action(engine(), 2, id.bytes(), byteArrayOf())
    @Synchronized override fun join(id: String, welcome: ByteArray) { ReaktorSecurityNative.action(engine(), 4, id.bytes(), welcome) }
    @Synchronized override fun process(id: String, commit: ByteArray) = ReaktorSecurityNative.action(engine(), 5, id.bytes(), commit) != 0L
    @Synchronized override fun seal(id: String, envelope: ByteArray, plaintext: ByteArray, circleGame: Boolean, anonymous: Boolean): EngineSealed {
        val result = ReaktorSecurityNative.seal(engine(), id.bytes(), envelope, plaintext, circleGame, anonymous)
        check(result.size == 3)
        return EngineSealed(result[0], result[1], result[2])
    }
    @Synchronized override fun open(id: String, envelope: ByteArray, sealed: EngineSealed, circleGame: Boolean) =
        ReaktorSecurityNative.open(engine(), false, id.bytes(), envelope, sealed.header, sealed.commitment, sealed.ciphertext, circleGame).opened()
}

private class AndroidKeyRing(private var handle: Long) : EngineKeyRing {
    @Synchronized override fun close() { if (handle != 0L) ReaktorSecurityNative.destroy(handle, true); handle = 0 }
    @Synchronized override fun open(id: String, envelope: ByteArray, sealed: EngineSealed, circleGame: Boolean): EngineOpened {
        check(handle != 0L) { "Key ring is closed" }
        return ReaktorSecurityNative.open(handle, true, id.bytes(), envelope, sealed.header, sealed.commitment, sealed.ciphertext, circleGame).opened()
    }
}
