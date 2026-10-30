@file:Suppress("NON_EXPORTABLE_TYPE")

package dev.shibasis.reaktor.security

import dev.shibasis.reaktor.service.Callers
import dev.shibasis.reaktor.service.ServiceAccess
import dev.shibasis.reaktor.service.contract.Service
import kotlinx.serialization.Serializable
import kotlin.js.JsExport

@JsExport
@Serializable
data class DeviceRegistration(val deviceId: String, val signatureKey: ByteArray, val cipherSuite: Int, val proof: ByteArray)

@JsExport
@Serializable
data class DirectoryDevice(val deviceId: String, val userId: String, val credential: ByteArray, val signatureKey: ByteArray, val cipherSuite: Int)

@JsExport
@Serializable
data class PublishedKeyPackage(val reference: String, val bytes: ByteArray, val lastResort: Boolean = false)

@JsExport
@Serializable
data class KeyPackagePublication(val deviceId: String, val packages: List<PublishedKeyPackage>)

@JsExport
@Serializable
data class DirectoryLookup(val conversationId: String, val userIds: Set<String>)

@JsExport
@Serializable
data class DirectoryKeyPackage(val device: DirectoryDevice, val reference: String, val bytes: ByteArray, val lastResort: Boolean)

@JsExport
@Serializable
data class DirectoryWelcome(val id: String, val deviceId: String, val conversationId: String, val epoch: Long, val bytes: ByteArray, val members: Set<String> = emptySet())

@JsExport
@Serializable
data class CommitPublication(
    val conversationId: String,
    val senderDeviceId: String,
    val previousEpoch: Long?,
    val epoch: Long,
    val commit: ByteArray,
    val groupInfo: ByteArray,
    val added: Set<String>,
    val removed: Set<String>,
    val welcomes: List<DirectoryWelcome>,
)

@JsExport
@Serializable
data class DirectoryCommit(val conversationId: String, val epoch: Long, val bytes: ByteArray, val senderDeviceId: String, val added: Set<String>, val removed: Set<String>, val members: Set<String> = emptySet(), val roster: Set<String> = emptySet())

@JsExport
@Serializable
data class CommitAcceptance(val accepted: Boolean, val epoch: Long?)

@JsExport
@Serializable
data class CommitCursor(val conversationId: String, val afterEpoch: Long = -1)

@JsExport
@Serializable
data class DirectoryGroupInfo(val epoch: Long?, val bytes: ByteArray?, val members: Set<String> = emptySet(), val roster: Set<String> = emptySet())

@JsExport
@Serializable
data class WelcomeConsumption(val deviceId: String, val ids: Set<String>)

@JsExport
open class SecureDirectoryApi : Service("reaktor.security") {
    override val access = ServiceAccess(Callers.SignedIn)
    open val register by post<DeviceRegistration, DirectoryDevice>("/security/devices")
    open val publishPackages by post<KeyPackagePublication, Int>("/security/key-packages")
    open val devices by post<DirectoryLookup, List<DirectoryDevice>>("/security/devices/list")
    open val claimPackages by post<DirectoryLookup, List<DirectoryKeyPackage>>("/security/key-packages/claim")
    open val revoke by post<String, Unit>("/security/devices/revoke")
    open val publishCommit by post<CommitPublication, CommitAcceptance>("/security/commits")
    open val commits by post<CommitCursor, List<DirectoryCommit>>("/security/commits/list")
    open val groupInfo by post<String, DirectoryGroupInfo>("/security/group-info")
    open val welcomes by post<String, List<DirectoryWelcome>>("/security/welcomes")
    open val consumeWelcomes by post<WelcomeConsumption, Unit>("/security/welcomes/consume")
}

interface ConversationAuthority {
    suspend fun isMember(conversationId: String, userId: String): Boolean
    suspend fun members(conversationId: String): Set<String>
}
