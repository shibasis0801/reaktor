@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.shibasis.reaktor.security

import dev.shibasis.reaktor.security.native.*
import kotlinx.cinterop.*
import platform.posix.memset

private fun status(value: rsec_status_t) { if (value != RSEC_OK) throw NativeSecurityException(value.toInt()) }
private fun ULong.epoch(): Long = toLong().also { check(it >= 0) }
private fun rsec_buffer_t.bytes(): ByteArray {
    check(len <= (64uL * 1024uL * 1024uL + 4096uL))
    return if (len == 0uL) byteArrayOf() else checkNotNull(data).reinterpret<ByteVar>().readBytes(len.toInt())
}
private inline fun <reified T : CVariable> MemScope.zero(): T = alloc<T>().also { memset(it.ptr, 0, sizeOf<T>().convert()) }

private class Inputs(val scope: MemScope) {
    private val allocations = mutableListOf<Pair<CPointer<UByteVar>, Int>>()
    fun fill(target: rsec_buffer_t, bytes: ByteArray) {
        require(bytes.size <= 64 * 1024 * 1024 + 4096)
        val data = scope.allocArray<UByteVar>(maxOf(1, bytes.size))
        bytes.forEachIndexed { index, byte -> data[index] = byte.toUByte() }
        allocations += data to maxOf(1, bytes.size)
        target.data = data
        target.len = bytes.size.convert()
    }
    fun input(bytes: ByteArray): rsec_buffer_t = scope.zero<rsec_buffer_t>().also { fill(it, bytes) }
    fun text(value: String) = input(value.encodeToByteArray())
    fun list(values: List<ByteArray>): CPointer<rsec_buffer_t>? {
        require(values.size <= 4095)
        if (values.isEmpty()) return null
        return scope.allocArray<rsec_buffer_t>(values.size).also { buffers -> values.forEachIndexed { index, value -> fill(buffers[index], value) } }
    }
    fun policy(value: EnginePolicy): rsec_credential_policy_t {
        require(value.authKeys.size in 1..8)
        val keys = scope.allocArray<rsec_auth_key_t>(value.authKeys.size)
        value.authKeys.entries.forEachIndexed { index, entry -> fill(keys[index].id, entry.key.encodeToByteArray()); fill(keys[index].public_key, entry.value) }
        return scope.zero<rsec_credential_policy_t>().also {
            fill(it.tenant_id, value.tenantId.encodeToByteArray()); fill(it.app_id, value.appId.encodeToByteArray())
            it.auth_keys = keys
            it.auth_key_count = value.authKeys.size.convert()
        }
    }
    fun clear() { allocations.forEach { (data, length) -> memset(data, 0, length.convert()) } }
}
private fun <T> inputs(call: Inputs.() -> T): T = memScoped {
    val input = Inputs(this)
    try { input.call() } finally { input.clear() }
}
private fun buffer(call: (CPointer<rsec_buffer_t>) -> rsec_status_t): ByteArray = memScoped {
    val out = zero<rsec_buffer_t>()
    try { status(call(out.ptr)); out.bytes() } finally { rsec_buffer_free(out.readValue()) }
}
private fun commit(call: (CPointer<rsec_commit_t>) -> rsec_status_t): EngineCommit = memScoped {
    val out = zero<rsec_commit_t>()
    try { status(call(out.ptr)); EngineCommit(out.welcome.bytes(), out.commit.bytes(), out.group_info.bytes(), out.epoch.epoch()) }
    finally { rsec_commit_free(out.readValue()) }
}
private fun opened(sealed: EngineSealed, call: (CPointer<rsec_sealed_content_t>, CPointer<rsec_opened_content_t>) -> rsec_status_t): EngineOpened = inputs {
    val content = scope.zero<rsec_sealed_content_t>()
    fill(content.header, sealed.header); fill(content.commitment, sealed.commitment); fill(content.ciphertext, sealed.ciphertext)
    val out = scope.zero<rsec_opened_content_t>()
    try {
        status(call(content.ptr, out.ptr))
        EngineOpened(out.plaintext.bytes(), out.franking_key.bytes(), out.sender_credential.bytes(), out.authenticated != 0.toUByte(), out.duplicate != 0.toUByte())
    } finally { rsec_opened_content_free(out.readValue()) }
}

internal actual object SecurityEngineFactory {
    actual fun create(policy: EnginePolicy, suite: MlsCipherSuiteId): SecurityEngine = inputs {
        check(rsec_abi_version() == 2u)
        val out = scope.alloc<CPointerVar<rsec_engine_t>>()
        out.value = null
        status(rsec_engine_create_verified(policy(policy).ptr, suite.code.toUShort(), out.ptr))
        DarwinSecurityEngine(checkNotNull(out.value))
    }
    actual fun restore(snapshot: ByteArray, policy: EnginePolicy): SecurityEngine = inputs {
        check(rsec_abi_version() == 2u)
        val state = input(snapshot)
        val out = scope.alloc<CPointerVar<rsec_engine_t>>()
        out.value = null
        status(rsec_engine_restore_verified(policy(policy).ptr, state.data, state.len, out.ptr))
        DarwinSecurityEngine(checkNotNull(out.value))
    }
    actual fun keyRing(encoded: ByteArray, policy: EnginePolicy): EngineKeyRing = inputs {
        val state = input(encoded)
        val out = scope.alloc<CPointerVar<rsec_key_ring_t>>()
        out.value = null
        status(rsec_key_ring_create(policy(policy).ptr, state.data, state.len, out.ptr))
        DarwinKeyRing(checkNotNull(out.value))
    }
    actual fun storageKey() = buffer(::rsec_storage_key)
    actual fun sealStorage(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray = inputs {
        val secret = input(key); val envelope = input(aad); val content = input(plaintext)
        buffer { rsec_storage_seal(secret.data, secret.len, envelope.data, envelope.len, content.data, content.len, it) }
    }
    actual fun openStorage(key: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray = inputs {
        val secret = input(key); val envelope = input(aad); val content = input(ciphertext)
        buffer { rsec_storage_open(secret.data, secret.len, envelope.data, envelope.len, content.data, content.len, it) }
    }
}

private class DarwinSecurityEngine(private var handle: CPointer<rsec_engine_t>?) : SecurityEngine {
    private fun engine() = checkNotNull(handle) { "Security engine is closed" }
    override fun close() { handle?.let(::rsec_engine_destroy); handle = null }
    override fun snapshot() = buffer { rsec_engine_snapshot(engine(), it) }
    override fun exportKeyRing() = buffer { rsec_engine_key_ring(engine(), it) }
    override fun publicKey() = buffer { rsec_device_public_key(engine(), it) }
    override fun keyPackage() = buffer { rsec_generate_key_package(engine(), it) }
    override fun lastResortKeyPackage() = buffer { rsec_last_resort_key_package(engine(), it) }
    override fun proveDevice(user: String, device: String): ByteArray = inputs {
        val u = text(user); val d = text(device)
        buffer { rsec_device_proof(engine(), u.data, u.len, d.data, d.len, it) }
    }
    override fun installCredential(credential: ByteArray) = inputs { val value = input(credential); status(rsec_install_credential(engine(), value.data, value.len)) }
    override fun setMembers(id: String, users: Set<String>) = inputs {
        val name = text(id)
        status(rsec_set_members(engine(), name.data, name.len, list(users.map(String::encodeToByteArray)), users.size.convert()))
    }
    override fun roster(id: String): List<ByteArray> = inputs {
        val name = text(id)
        val count = scope.alloc<ULongVar>()
        status(rsec_roster_count(engine(), name.data, name.len, count.ptr))
        check(count.value <= 4096uL)
        List(count.value.toInt()) { index -> buffer { rsec_roster_credential(engine(), name.data, name.len, index.convert(), it) } }
    }
    override fun pending(id: String): EngineCommit = inputs { val name = text(id); commit { rsec_pending_commit(engine(), name.data, name.len, it) } }
    override fun prepareCreate(id: String, packages: List<ByteArray>): EngineCommit = inputs {
        val name = text(id); val values = list(packages)
        commit { rsec_prepare_create(engine(), name.data, name.len, values, packages.size.convert(), it) }
    }
    override fun prepareAdd(id: String, packages: List<ByteArray>): EngineCommit = inputs {
        val name = text(id); val values = list(packages)
        commit { rsec_prepare_add(engine(), name.data, name.len, values, packages.size.convert(), it) }
    }
    override fun prepareRemove(id: String, index: Int): EngineCommit = inputs {
        require(index >= 0)
        val name = text(id)
        commit { rsec_prepare_remove(engine(), name.data, name.len, index.toUInt(), it) }
    }
    override fun prepareMembership(id: String, packages: List<ByteArray>, removals: List<Int>): EngineCommit = inputs {
        val name = text(id); val values = list(packages); val indices = input(encodeRosterIndices(removals))
        commit { rsec_prepare_membership(engine(), name.data, name.len, values, packages.size.convert(), indices.data, indices.len, it) }
    }
    override fun prepareRotate(id: String): EngineCommit = inputs { val name = text(id); commit { rsec_prepare_rotate(engine(), name.data, name.len, it) } }
    override fun prepareExternalJoin(id: String, info: ByteArray): EngineCommit = inputs {
        val name = text(id); val value = input(info)
        commit { rsec_prepare_external_join(engine(), name.data, name.len, value.data, value.len, it) }
    }
    override fun accept(id: String, commit: ByteArray): Long = inputs {
        val name = text(id); val value = input(commit); val out = scope.alloc<ULongVar>()
        status(rsec_accept_pending(engine(), name.data, name.len, value.data, value.len, out.ptr)); out.value.epoch()
    }
    override fun discard(id: String) = inputs { val name = text(id); status(rsec_discard_pending(engine(), name.data, name.len)) }
    override fun epoch(id: String): Long = inputs {
        val name = text(id); val out = scope.alloc<ULongVar>()
        status(rsec_epoch(engine(), name.data, name.len, out.ptr)); out.value.epoch()
    }
    override fun join(id: String, welcome: ByteArray) = inputs {
        val name = text(id); val value = input(welcome)
        status(rsec_join_group(engine(), name.data, name.len, value.data, value.len))
    }
    override fun process(id: String, commit: ByteArray): Boolean = inputs {
        val name = text(id); val value = input(commit); val out = scope.alloc<UByteVar>()
        status(rsec_process_handshake(engine(), name.data, name.len, value.data, value.len, out.ptr)); out.value != 0.toUByte()
    }
    override fun seal(id: String, envelope: ByteArray, plaintext: ByteArray, circleGame: Boolean, anonymous: Boolean): EngineSealed = inputs {
        val name = text(id); val aad = input(envelope); val content = input(plaintext); val out = scope.zero<rsec_sealed_content_t>()
        try {
            status(rsec_seal_content(engine(), name.data, name.len, aad.data, aad.len, content.data, content.len,
                if (circleGame) 1.toUByte() else 0.toUByte(), if (anonymous) 1.toUByte() else 0.toUByte(), out.ptr))
            EngineSealed(out.header.bytes(), out.commitment.bytes(), out.ciphertext.bytes())
        } finally { rsec_sealed_content_free(out.readValue()) }
    }
    override fun open(id: String, envelope: ByteArray, sealed: EngineSealed, circleGame: Boolean): EngineOpened = inputs {
        val name = text(id); val aad = input(envelope)
        opened(sealed) { content, out -> rsec_open_content(engine(), name.data, name.len, aad.data, aad.len, content, if (circleGame) 1.toUByte() else 0.toUByte(), out) }
    }
}

private class DarwinKeyRing(private var handle: CPointer<rsec_key_ring_t>?) : EngineKeyRing {
    override fun close() { handle?.let(::rsec_key_ring_destroy); handle = null }
    override fun open(id: String, envelope: ByteArray, sealed: EngineSealed, circleGame: Boolean): EngineOpened = inputs {
        val ring = checkNotNull(handle) { "Key ring is closed" }; val name = text(id); val aad = input(envelope)
        opened(sealed) { content, out -> rsec_key_ring_open(ring, name.data, name.len, aad.data, aad.len, content, if (circleGame) 1.toUByte() else 0.toUByte(), out) }
    }
}
