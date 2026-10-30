package dev.shibasis.reaktor.security

import dev.shibasis.reaktor.service.contract.reject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MemorySecureDirectoryState {
    internal val mutex = Mutex()
    internal val scopes = mutableMapOf<Pair<String, String>, MemoryDirectoryScope>()
}

internal class MemoryDirectoryScope {
    val devices = linkedMapOf<String, DirectoryDevice>()
    val revoked = mutableSetOf<String>()
    val packages = linkedMapOf<String, Pair<String, PublishedKeyPackage>>()
    val commits = mutableMapOf<String, MutableList<DirectoryCommit>>()
    val groupInfo = mutableMapOf<String, ByteArray>()
    val welcomes = linkedMapOf<String, DirectoryWelcome>()
}

class MemorySecureDirectory(
    tenantId: String,
    appId: String,
    private val userId: String,
    private val state: MemorySecureDirectoryState,
    private val authority: ConversationAuthority,
    private val enroll: suspend (DeviceRegistration) -> ByteArray,
) : SecureDirectoryApi() {
    private val scopeId = tenantId to appId

    private suspend fun <T> locked(block: suspend MemoryDirectoryScope.() -> T): T = state.mutex.withLock {
        state.scopes.getOrPut(scopeId, ::MemoryDirectoryScope).block()
    }

    override val register = super.register.handle { request -> locked {
        valid(request.deviceId.isNotBlank() && request.cipherSuite in 1..3 && request.proof.isNotEmpty())
        valid(request.signatureKey.size == if (request.cipherSuite == 2) 65 else 32)
        devices[request.deviceId]?.let { previous ->
            if (previous.userId != userId || previous.cipherSuite != request.cipherSuite ||
                !previous.signatureKey.contentEquals(request.signatureKey) || request.deviceId in revoked) reject(409, "Device identity already exists")
        }
        val device = DirectoryDevice(request.deviceId, userId, enroll(request).copyOf(), request.signatureKey.copyOf(), request.cipherSuite)
        valid(device.credential.isNotEmpty())
        devices[request.deviceId] = device
        device.detached()
    } }

    override val publishPackages = super.publishPackages.handle { request -> locked {
        own(request.deviceId)
        valid(request.packages.size in 0..256 && request.packages.map { it.reference }.toSet().size == request.packages.size)
        valid(request.packages.count { it.lastResort } <= 1)
        val next = LinkedHashMap(packages)
        request.packages.forEach { value ->
            valid(value.reference.isNotBlank() && value.bytes.size in 1..1048576)
            next[value.reference]?.let { existing ->
                if (existing.first != request.deviceId || !existing.second.bytes.contentEquals(value.bytes) ||
                    existing.second.lastResort != value.lastResort) reject(409, "Key package reference already exists")
            }
            if (value.lastResort) next.entries.removeAll { it.value.first == request.deviceId && it.value.second.lastResort && it.key != value.reference }
            next[value.reference] = request.deviceId to value.copy(bytes = value.bytes.copyOf())
        }
        valid(next.values.count { it.first == request.deviceId } <= 4096)
        packages.clear(); packages.putAll(next)
        packages.values.count { it.first == request.deviceId }
    } }

    override val devices = super.devices.handle { request -> locked { lookup(request).map { it.detached() } } }

    override val claimPackages = super.claimPackages.handle { request -> locked {
        val claimed = lookup(request).map { device ->
            val available = packages.values.filter { it.first == device.deviceId }.map { it.second }
            val value = available.firstOrNull { !it.lastResort } ?: available.firstOrNull()
                ?: reject(409, "A member device needs key packages")
            DirectoryKeyPackage(device.detached(), value.reference, value.bytes.copyOf(), value.lastResort)
        }
        claimed.filter { !it.lastResort }.forEach { packages.remove(it.reference) }
        claimed
    } }

    override val revoke = super.revoke.handle { deviceId -> locked {
        own(deviceId)
        revoked += deviceId
        packages.entries.removeAll { it.value.first == deviceId }
        Unit
    } }

    override val publishCommit = super.publishCommit.handle { request -> locked {
        member(request.conversationId)
        own(request.senderDeviceId)
        valid(request.epoch > 0 && request.epoch < Long.MAX_VALUE)
        valid(request.previousEpoch?.let { it >= 0 && request.epoch == it + 1 } ?: (request.epoch == 1L))
        valid(request.commit.isNotEmpty() && request.groupInfo.isNotEmpty() && request.added.intersect(request.removed).isEmpty())
        valid(request.welcomes.map { it.id }.toSet().size == request.welcomes.size)
        valid(request.welcomes.map { it.deviceId }.toSet().size == request.welcomes.size)
        valid((request.added - request.senderDeviceId).all { id -> request.welcomes.any { it.deviceId == id } })
        valid(request.welcomes.all { it.deviceId in request.added && it.conversationId == request.conversationId && it.epoch == request.epoch && it.bytes.isNotEmpty() })
        val history = commits.getOrPut(request.conversationId) { mutableListOf() }
        val previous = history.lastOrNull()
        val existing = history.firstOrNull { it.epoch == request.epoch }
        if (existing != null && existing.senderDeviceId == request.senderDeviceId && existing.bytes.contentEquals(request.commit)) {
            CommitAcceptance(true, request.epoch)
        } else if (previous?.epoch != request.previousEpoch) {
            CommitAcceptance(false, previous?.epoch)
        } else {
            val active = lookup(DirectoryLookup(request.conversationId, authority.members(request.conversationId))).map { it.deviceId }.toSet()
            valid(request.senderDeviceId in active && active.containsAll(request.added))
            val oldRoster = previous?.roster.orEmpty()
            valid(oldRoster.containsAll(request.removed) && request.added.intersect(oldRoster).isEmpty())
            val nextRoster = oldRoster - request.removed + request.added
            valid(nextRoster.size in 1..4096 && request.senderDeviceId in nextRoster)
            if (request.previousEpoch == null) valid(nextRoster == active)
            val epochMembers = nextRoster.map { checkNotNull(devices[it]).userId }.toSet()
            valid(request.welcomes.none { it.id in welcomes })
            history += DirectoryCommit(request.conversationId, request.epoch, request.commit.copyOf(), request.senderDeviceId,
                request.added.toSet(), request.removed.toSet(), epochMembers, nextRoster)
            groupInfo[request.conversationId] = request.groupInfo.copyOf()
            request.welcomes.forEach { welcomes[it.id] = it.copy(bytes = it.bytes.copyOf(), members = epochMembers) }
            CommitAcceptance(true, request.epoch)
        }
    } }

    override val commits = super.commits.handle { request -> locked {
        member(request.conversationId)
        valid(request.afterEpoch >= -1)
        commits[request.conversationId].orEmpty().filter { it.epoch > request.afterEpoch }.take(512)
            .map { it.copy(bytes = it.bytes.copyOf(), added = it.added.toSet(), removed = it.removed.toSet(), members = it.members.toSet(), roster = it.roster.toSet()) }
    } }

    override val groupInfo = super.groupInfo.handle { conversationId -> locked {
        member(conversationId)
        val latest = commits[conversationId]?.lastOrNull()
        DirectoryGroupInfo(latest?.epoch, groupInfo[conversationId]?.copyOf(), latest?.members.orEmpty().toSet(), latest?.roster.orEmpty().toSet())
    } }

    override val welcomes = super.welcomes.handle { deviceId -> locked {
        own(deviceId)
        welcomes.values.filter { it.deviceId == deviceId }.take(512).map { it.copy(bytes = it.bytes.copyOf(), members = it.members.toSet()) }
    } }

    override val consumeWelcomes = super.consumeWelcomes.handle { request -> locked {
        own(request.deviceId)
        valid(request.ids.size in 1..512)
        request.ids.forEach { id -> if (welcomes[id]?.deviceId == request.deviceId) welcomes.remove(id) }
        Unit
    } }

    private suspend fun MemoryDirectoryScope.lookup(request: DirectoryLookup): List<DirectoryDevice> {
        member(request.conversationId)
        valid(request.userIds.size <= 4096)
        val members = authority.members(request.conversationId)
        if (!members.containsAll(request.userIds)) reject(403, "Users must belong to the conversation")
        val users = request.userIds.ifEmpty { members }
        return devices.values.filter { it.userId in users && it.deviceId !in revoked }.sortedWith(compareBy({ it.userId }, { it.deviceId }))
    }

    private suspend fun member(conversationId: String) {
        if (!authority.isMember(conversationId, userId)) reject(403, "Conversation membership required")
    }

    private fun MemoryDirectoryScope.own(deviceId: String) {
        if (devices[deviceId]?.userId != userId || deviceId in revoked) reject(403, "Active device ownership required")
    }

    private fun DirectoryDevice.detached() = copy(credential = credential.copyOf(), signatureKey = signatureKey.copyOf())
    private fun valid(condition: Boolean) { if (!condition) reject(400, "Invalid security directory request") }
}
