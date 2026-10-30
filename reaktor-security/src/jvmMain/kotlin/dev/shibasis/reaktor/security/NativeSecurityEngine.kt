package dev.shibasis.reaktor.security

import com.sun.jna.IntegerType
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.ByteByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

internal class SizeT(value: Long = 0) : IntegerType(Native.SIZE_T_SIZE, value, true) {
    override fun toShort(): Short = toLong().toShort()
    override fun toByte(): Byte = toLong().toByte()
}

@Structure.FieldOrder("data", "len")
internal open class NativeBuffer : Structure() {
    @JvmField var data: Pointer? = null
    @JvmField var len = SizeT()
    class Value : NativeBuffer(), Structure.ByValue
    fun bytes(): ByteArray {
        val length = len.toLong()
        check(length in 0..(64L * 1024 * 1024 + 4096))
        return if (length == 0L) byteArrayOf() else checkNotNull(data).getByteArray(0, length.toInt())
    }
    fun value() = Value().also { it.data = data; it.len = len }
}

@Structure.FieldOrder("credential", "credentialLength", "suite")
internal class NativeEngineConfig : Structure() {
    @JvmField var credential: Pointer? = null
    @JvmField var credentialLength = SizeT()
    @JvmField var suite: Short = 3
}


@Structure.FieldOrder("id", "publicKey")
internal open class NativeAuthKey : Structure() {
    @JvmField var id = NativeBuffer()
    @JvmField var publicKey = NativeBuffer()
}

@Structure.FieldOrder("tenant", "app", "keys", "keyCount")
internal class NativeCredentialPolicy : Structure() {
    @JvmField var tenant = NativeBuffer()
    @JvmField var app = NativeBuffer()
    @JvmField var keys: Pointer? = null
    @JvmField var keyCount = SizeT()
}

@Structure.FieldOrder("welcome", "commit", "groupInfo", "epoch")
internal class NativeCommit : Structure() {
    @JvmField var welcome = NativeBuffer()
    @JvmField var commit = NativeBuffer()
    @JvmField var groupInfo = NativeBuffer()
    @JvmField var epoch: Long = 0
}

@Structure.FieldOrder("header", "commitment", "ciphertext")
internal class NativeSealed : Structure() {
    @JvmField var header = NativeBuffer()
    @JvmField var commitment = NativeBuffer()
    @JvmField var ciphertext = NativeBuffer()
}

@Structure.FieldOrder("plaintext", "frankingKey", "credential", "authenticated", "duplicate")
internal class NativeOpened : Structure() {
    @JvmField var plaintext = NativeBuffer()
    @JvmField var frankingKey = NativeBuffer()
    @JvmField var credential = NativeBuffer()
    @JvmField var authenticated: Byte = 0
    @JvmField var duplicate: Byte = 0
}

internal interface SecurityNativeApi : Library {
    fun rsec_engine_key_ring(engine: Pointer, out: NativeBuffer): Int
    fun rsec_key_ring_create(policy: NativeCredentialPolicy?, encoded: ByteArray, len: SizeT, out: PointerByReference): Int
    fun rsec_key_ring_open(ring: Pointer, id: ByteArray, idLen: SizeT, envelope: ByteArray, envelopeLen: SizeT, content: NativeSealed, purpose: Byte, out: NativeOpened): Int
    fun rsec_key_ring_destroy(ring: Pointer)
    fun rsec_abi_version(): Int
    fun rsec_engine_create_verified(policy: NativeCredentialPolicy, suite: Short, out: PointerByReference): Int
    fun rsec_engine_restore_verified(policy: NativeCredentialPolicy, snapshot: ByteArray, len: SizeT, out: PointerByReference): Int
    fun rsec_install_credential(engine: Pointer, credential: ByteArray, len: SizeT): Int
    fun rsec_set_members(engine: Pointer, id: ByteArray, idLen: SizeT, users: Pointer?, count: SizeT): Int
    fun rsec_roster_count(engine: Pointer, id: ByteArray, idLen: SizeT, out: Pointer): Int
    fun rsec_roster_credential(engine: Pointer, id: ByteArray, idLen: SizeT, index: SizeT, out: NativeBuffer): Int
    fun rsec_last_resort_key_package(engine: Pointer, out: NativeBuffer): Int
    fun rsec_pending_commit(engine: Pointer, id: ByteArray, idLen: SizeT, out: NativeCommit): Int

    fun rsec_engine_create(config: NativeEngineConfig, out: PointerByReference): Int
    fun rsec_engine_destroy(engine: Pointer)
    fun rsec_engine_snapshot(engine: Pointer, out: NativeBuffer): Int
    fun rsec_engine_restore(snapshot: ByteArray, len: SizeT, out: PointerByReference): Int
    fun rsec_device_public_key(engine: Pointer, out: NativeBuffer): Int
    fun rsec_device_proof(engine: Pointer, user: ByteArray, userLen: SizeT, device: ByteArray, deviceLen: SizeT, out: NativeBuffer): Int
    fun rsec_generate_key_package(engine: Pointer, out: NativeBuffer): Int
    fun rsec_prepare_create(engine: Pointer, id: ByteArray, idLen: SizeT, packages: Pointer?, count: SizeT, out: NativeCommit): Int
    fun rsec_prepare_add(engine: Pointer, id: ByteArray, idLen: SizeT, packages: Pointer?, count: SizeT, out: NativeCommit): Int
    fun rsec_prepare_remove(engine: Pointer, id: ByteArray, idLen: SizeT, index: Int, out: NativeCommit): Int
    fun rsec_prepare_membership(engine: Pointer, id: ByteArray, idLen: SizeT, packages: Pointer?, count: SizeT, removals: ByteArray, len: SizeT, out: NativeCommit): Int
    fun rsec_prepare_rotate(engine: Pointer, id: ByteArray, idLen: SizeT, out: NativeCommit): Int
    fun rsec_prepare_external_join(engine: Pointer, id: ByteArray, idLen: SizeT, info: ByteArray, infoLen: SizeT, out: NativeCommit): Int
    fun rsec_accept_pending(engine: Pointer, id: ByteArray, idLen: SizeT, commit: ByteArray, len: SizeT, out: LongByReference): Int
    fun rsec_discard_pending(engine: Pointer, id: ByteArray, idLen: SizeT): Int
    fun rsec_epoch(engine: Pointer, id: ByteArray, idLen: SizeT, out: LongByReference): Int
    fun rsec_join_group(engine: Pointer, id: ByteArray, idLen: SizeT, welcome: ByteArray, len: SizeT): Int
    fun rsec_process_handshake(engine: Pointer, id: ByteArray, idLen: SizeT, commit: ByteArray, len: SizeT, out: ByteByReference): Int
    fun rsec_seal_content(engine: Pointer, id: ByteArray, idLen: SizeT, envelope: ByteArray, envelopeLen: SizeT, plaintext: ByteArray, len: SizeT, purpose: Byte, anonymous: Byte, out: NativeSealed): Int
    fun rsec_open_content(engine: Pointer, id: ByteArray, idLen: SizeT, envelope: ByteArray, envelopeLen: SizeT, content: NativeSealed, purpose: Byte, out: NativeOpened): Int
    fun rsec_buffer_free(buffer: NativeBuffer.Value)
    fun rsec_storage_key(out: NativeBuffer): Int
    fun rsec_storage_seal(key: ByteArray, keyLen: SizeT, aad: ByteArray, aadLen: SizeT, plaintext: ByteArray, len: SizeT, out: NativeBuffer): Int
    fun rsec_storage_open(key: ByteArray, keyLen: SizeT, aad: ByteArray, aadLen: SizeT, ciphertext: ByteArray, len: SizeT, out: NativeBuffer): Int
}

internal class NativeSecurityEngine private constructor(private var handle: Pointer?) : SecurityEngine {
    private fun engine() = checkNotNull(handle) { "Security engine is closed" }

    @Synchronized override fun close() {
        handle?.let(api::rsec_engine_destroy)
        handle = null
    }

    @Synchronized override fun snapshot(): ByteArray = buffer { api.rsec_engine_snapshot(engine(), it) }
    @Synchronized override fun exportKeyRing(): ByteArray = buffer { api.rsec_engine_key_ring(engine(), it) }
    @Synchronized override fun publicKey(): ByteArray = buffer { api.rsec_device_public_key(engine(), it) }
    @Synchronized override fun proveDevice(user: String, device: String): ByteArray = buffer {
        api.rsec_device_proof(engine(), user.encodeToByteArray(), user.size(), device.encodeToByteArray(), device.size(), it)
    }
    @Synchronized override fun keyPackage(): ByteArray = buffer { api.rsec_generate_key_package(engine(), it) }


    @Synchronized override fun installCredential(credential: ByteArray) =
        status(api.rsec_install_credential(engine(), credential, credential.sizeT()))
    @Synchronized override fun setMembers(id: String, users: Set<String>) = packages(users.map(String::encodeToByteArray)) { pointer, count ->
        status(api.rsec_set_members(engine(), id.encodeToByteArray(), id.size(), pointer, count))
    }
    @Synchronized override fun roster(id: String): List<ByteArray> = Memory(Native.SIZE_T_SIZE.toLong()).use { count ->
        status(api.rsec_roster_count(engine(), id.encodeToByteArray(), id.size(), count))
        val length = if (Native.SIZE_T_SIZE == 8) count.getLong(0) else Integer.toUnsignedLong(count.getInt(0))
        check(length in 0..4096)
        List(length.toInt()) { index ->
            buffer { api.rsec_roster_credential(engine(), id.encodeToByteArray(), id.size(), SizeT(index.toLong()), it) }
        }
    }
    @Synchronized override fun lastResortKeyPackage(): ByteArray = buffer { api.rsec_last_resort_key_package(engine(), it) }
    @Synchronized override fun pending(id: String): EngineCommit = commit {
        api.rsec_pending_commit(engine(), id.encodeToByteArray(), id.size(), it)
    }

    @Synchronized override fun prepareCreate(id: String, packages: List<ByteArray>): EngineCommit = packages(packages) { pointer, count ->
        commit { api.rsec_prepare_create(engine(), id.encodeToByteArray(), id.size(), pointer, count, it) }
    }

    @Synchronized override fun prepareAdd(id: String, packages: List<ByteArray>): EngineCommit = packages(packages) { pointer, count ->
        commit { api.rsec_prepare_add(engine(), id.encodeToByteArray(), id.size(), pointer, count, it) }
    }

    @Synchronized override fun prepareRemove(id: String, index: Int): EngineCommit = commit {
        api.rsec_prepare_remove(engine(), id.encodeToByteArray(), id.size(), index, it)
    }
    @Synchronized override fun prepareMembership(id: String, packages: List<ByteArray>, removals: List<Int>): EngineCommit =
        packages(packages) { pointer, count ->
            val indices = encodeRosterIndices(removals)
            commit { api.rsec_prepare_membership(engine(), id.encodeToByteArray(), id.size(), pointer, count, indices, indices.sizeT(), it) }
        }

    @Synchronized override fun prepareRotate(id: String): EngineCommit = commit {
        api.rsec_prepare_rotate(engine(), id.encodeToByteArray(), id.size(), it)
    }

    @Synchronized override fun prepareExternalJoin(id: String, info: ByteArray): EngineCommit = commit {
        api.rsec_prepare_external_join(engine(), id.encodeToByteArray(), id.size(), info, info.sizeT(), it)
    }

    @Synchronized override fun accept(id: String, commit: ByteArray): Long = LongByReference().let {
        status(api.rsec_accept_pending(engine(), id.encodeToByteArray(), id.size(), commit, commit.sizeT(), it)); it.value
    }

    @Synchronized override fun discard(id: String) = status(api.rsec_discard_pending(engine(), id.encodeToByteArray(), id.size()))
    @Synchronized override fun epoch(id: String): Long = LongByReference().let {
        status(api.rsec_epoch(engine(), id.encodeToByteArray(), id.size(), it)); it.value
    }

    @Synchronized override fun join(id: String, welcome: ByteArray) = status(
        api.rsec_join_group(engine(), id.encodeToByteArray(), id.size(), welcome, welcome.sizeT()))

    @Synchronized override fun process(id: String, commit: ByteArray): Boolean = ByteByReference().let {
        status(api.rsec_process_handshake(engine(), id.encodeToByteArray(), id.size(), commit, commit.sizeT(), it)); it.value.toInt() != 0
    }

    @Synchronized override fun seal(id: String, envelope: ByteArray, plaintext: ByteArray, circleGame: Boolean, anonymous: Boolean): EngineSealed {
        val out = NativeSealed()
        try {
            status(api.rsec_seal_content(engine(), id.encodeToByteArray(), id.size(), envelope, envelope.sizeT(),
                plaintext, plaintext.sizeT(), if (circleGame) 1 else 0, if (anonymous) 1 else 0, out))
            return EngineSealed(out.header.bytes(), out.commitment.bytes(), out.ciphertext.bytes())
        } finally { free(out.header, out.commitment, out.ciphertext) }
    }

    @Synchronized override fun open(id: String, envelope: ByteArray, sealed: EngineSealed, circleGame: Boolean): EngineOpened =
        opened(sealed) { input, out ->
            api.rsec_open_content(engine(), id.encodeToByteArray(), id.size(), envelope, envelope.sizeT(), input,
                if (circleGame) 1 else 0, out)
        }

    class KeyRing internal constructor(private var handle: Pointer?) : EngineKeyRing {
        @Synchronized override fun close() {
            handle?.let(api::rsec_key_ring_destroy)
            handle = null
        }
        @Synchronized override fun open(id: String, envelope: ByteArray, sealed: EngineSealed, circleGame: Boolean): EngineOpened =
            opened(sealed) { input, out ->
                api.rsec_key_ring_open(checkNotNull(handle) { "Key ring is closed" },
                    id.encodeToByteArray(), id.size(), envelope, envelope.sizeT(), input,
                    if (circleGame) 1 else 0, out)
            }
    }

    companion object {
        private val api: SecurityNativeApi by lazy {
            Native.load("ReaktorSecurity", SecurityNativeApi::class.java).also { check(it.rsec_abi_version() == 2) }
        }

        fun create(policy: EnginePolicy, suite: MlsCipherSuiteId = MlsCipherSuiteId.MLS_128_DHKEMX25519_CHACHA20POLY1305_SHA256_Ed25519): NativeSecurityEngine =
            policy(policy) { native ->
                val out = PointerByReference()
                status(api.rsec_engine_create_verified(native, suite.code.toShort(), out))
                NativeSecurityEngine(checkNotNull(out.value))
            }

        fun restore(snapshot: ByteArray, policy: EnginePolicy): NativeSecurityEngine = policy(policy) { native ->
            val out = PointerByReference()
            status(api.rsec_engine_restore_verified(native, snapshot, snapshot.sizeT(), out))
            NativeSecurityEngine(checkNotNull(out.value))
        }

        private fun <T> policy(value: EnginePolicy, call: (NativeCredentialPolicy) -> T): T {
            require(value.authKeys.size in 1..8)
            val native = NativeCredentialPolicy()
            val keys = NativeAuthKey().toArray(value.authKeys.size).map { it as NativeAuthKey }
            try {
                native.tenant.input(value.tenantId.encodeToByteArray())
                native.app.input(value.appId.encodeToByteArray())
                keys.zip(value.authKeys.entries).forEach { (key, entry) ->
                    key.id.input(entry.key.encodeToByteArray())
                    key.publicKey.input(entry.value)
                    key.write()
                }
                native.keys = keys.first().pointer
                native.keyCount = SizeT(keys.size.toLong())
                return call(native)
            } finally {
                listOf(native.tenant, native.app).plus(keys.flatMap { listOf(it.id, it.publicKey) })
                    .forEach { (it.data as? Memory)?.let { memory -> memory.clear(); memory.close() } }
            }
        }


        fun create(credential: ByteArray): NativeSecurityEngine {
            val config = NativeEngineConfig()
            val memory = memory(credential)
            config.credential = memory
            config.credentialLength = credential.sizeT()
            return try {
                val out = PointerByReference()
                status(api.rsec_engine_create(config, out))
                NativeSecurityEngine(checkNotNull(out.value))
            } finally { memory.close() }
        }

        fun restore(snapshot: ByteArray): NativeSecurityEngine {
            val out = PointerByReference()
            status(api.rsec_engine_restore(snapshot, snapshot.sizeT(), out))
            return NativeSecurityEngine(checkNotNull(out.value))
        }

        fun keyRing(encoded: ByteArray, pinned: EnginePolicy? = null): KeyRing {
            val out = PointerByReference()
            if (pinned == null) status(api.rsec_key_ring_create(null, encoded, encoded.sizeT(), out))
            else policy(pinned) { status(api.rsec_key_ring_create(it, encoded, encoded.sizeT(), out)) }
            return KeyRing(checkNotNull(out.value))
        }

        private fun opened(sealed: EngineSealed, call: (NativeSealed, NativeOpened) -> Int): EngineOpened {
            val input = NativeSealed().apply {
                header.input(sealed.header); commitment.input(sealed.commitment); ciphertext.input(sealed.ciphertext)
            }
            val out = NativeOpened()
            try {
                status(call(input, out))
                return EngineOpened(out.plaintext.bytes(), out.frankingKey.bytes(), out.credential.bytes(),
                    out.authenticated.toInt() != 0, out.duplicate.toInt() != 0)
            } finally {
                free(out.plaintext, out.frankingKey, out.credential)
                input.clearInputs()
            }
        }

        fun storageKey(): ByteArray = buffer(api::rsec_storage_key)
        fun sealStorage(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray = buffer {
            api.rsec_storage_seal(key, key.sizeT(), aad, aad.sizeT(), plaintext, plaintext.sizeT(), it)
        }
        fun openStorage(key: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray = buffer {
            api.rsec_storage_open(key, key.sizeT(), aad, aad.sizeT(), ciphertext, ciphertext.sizeT(), it)
        }

        private fun status(value: Int) { if (value != 0) throw NativeSecurityException(value) }
        private fun ByteArray.sizeT() = SizeT(size.toLong())
        private fun String.size() = encodeToByteArray().sizeT()
        private fun memory(bytes: ByteArray) = Memory(maxOf(1, bytes.size).toLong()).apply {
            if (bytes.isNotEmpty()) write(0, bytes, 0, bytes.size)
        }
        private fun NativeBuffer.input(bytes: ByteArray) { data = memory(bytes); len = bytes.sizeT() }
        private fun NativeSealed.clearInputs() { listOf(header, commitment, ciphertext).forEach { (it.data as Memory).close() } }
        private fun free(vararg buffers: NativeBuffer) = buffers.forEach { api.rsec_buffer_free(it.value()) }
        private fun buffer(call: (NativeBuffer) -> Int): ByteArray {
            val out = NativeBuffer()
            return try { status(call(out)); out.bytes() } finally { free(out) }
        }
        private fun commit(call: (NativeCommit) -> Int): EngineCommit {
            val out = NativeCommit()
            return try {
                status(call(out)); EngineCommit(out.welcome.bytes(), out.commit.bytes(), out.groupInfo.bytes(), out.epoch)
            } finally { free(out.welcome, out.commit, out.groupInfo) }
        }
        private fun <T> packages(values: List<ByteArray>, call: (Pointer?, SizeT) -> T): T {
            require(values.size <= 4095)
            if (values.isEmpty()) return call(null, SizeT())
            val buffers = NativeBuffer().toArray(values.size).map { it as NativeBuffer }
            try {
                buffers.zip(values).forEach { (buffer, value) -> buffer.input(value); buffer.write() }
                return call(buffers.first().pointer, SizeT(values.size.toLong()))
            } finally { buffers.forEach { (it.data as? Memory)?.close() } }
        }
    }
}
