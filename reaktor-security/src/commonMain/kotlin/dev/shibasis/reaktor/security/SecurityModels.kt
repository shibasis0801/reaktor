package dev.shibasis.reaktor.security

import kotlinx.serialization.Serializable

data class SecuritySession(val tenantId: String, val appId: String, val userId: String, val authKeys: Map<String, ByteArray>, val resetIdentity: Boolean = false) {
    init {
        require(listOf(tenantId, appId, userId).all { it.isNotBlank() && it.length <= 256 })
        require(authKeys.size in 1..8)
    }
}

enum class ConversationHealth { Waiting, Reconciling, Ready, Failed }

data class SecureRosterDevice(val userId: String, val deviceId: String, val fingerprint: String)
data class ConversationSecurityState(
    val conversationId: String,
    val epoch: Long? = null,
    val roster: List<SecureRosterDevice> = emptyList(),
    val health: ConversationHealth = ConversationHealth.Waiting,
    val firstJoinedEpoch: Long? = null,
)

@Serializable
data class SealedMessage(val epoch: Long, val senderDeviceId: String, val header: ByteArray, val commitment: ByteArray, val ciphertext: ByteArray)

data class OpenedMessage(val plaintext: ByteArray, val frankingKey: ByteArray, val sender: DeviceCredentialV1?, val authenticated: Boolean, val duplicate: Boolean)

internal fun readDeviceCredential(bytes: ByteArray): DeviceCredentialV1 {
    require(bytes.size in 1..32768)
    var offset = 0
    fun number(size: Int): Long {
        require(size in 1..8 && offset + size <= bytes.size)
        var result = 0L
        repeat(size) { result = (result shl 8) or (bytes[offset++].toLong() and 255) }
        return result
    }
    fun field(): ByteArray {
        val size = number(4)
        require(size in 1..4096 && offset + size <= bytes.size)
        return bytes.copyOfRange(offset, offset + size.toInt()).also { offset += size.toInt() }
    }
    fun text() = field().decodeToString(throwOnInvalidSequence = true)
    require(number(4) == 0x52534331L && number(2) == 1L)
    val tenant = TenantId(text())
    val app = AppId(text())
    val user = UserId(text())
    val device = DeviceId(text())
    val key = field()
    val count = number(2).toInt()
    require(count in 1..3)
    val suites = List(count) {
        val code = number(2).toInt()
        MlsCipherSuiteId.entries.single { it.code == code }
    }
    val created = number(8)
    val expires = number(8)
    val keyId = text()
    require(created >= 0 && expires > created && bytes.size - offset == 64)
    return DeviceCredentialV1(tenant, app, user, device, key, suites, created, expires, 1, keyId, bytes.copyOfRange(offset, bytes.size))
}

internal fun ByteArray.fingerprint(): String = joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

