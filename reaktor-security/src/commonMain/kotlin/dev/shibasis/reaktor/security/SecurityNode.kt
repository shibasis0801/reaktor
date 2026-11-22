package dev.shibasis.reaktor.security

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.db.core.ObjectStore
import dev.shibasis.reaktor.graph.consumes
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ActorNode
import dev.shibasis.reaktor.graph.core.node.Reply
import dev.shibasis.reaktor.graph.core.node.actorKey
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.ServiceExecutionPhase
import dev.shibasis.reaktor.service.ServiceSpan
import dev.shibasis.reaktor.service.SpanSink
import dev.shibasis.reaktor.service.TraceContext
import dev.shibasis.reaktor.service.contract.CallFailure
import dev.shibasis.reaktor.service.contract.Rejected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource
import dev.shibasis.reaktor.service.contract.asCallFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.time.Clock
import kotlin.uuid.Uuid

sealed interface SecurityCommand

private sealed interface SecurityRequest : SecurityCommand
private data class DeviceCommand(val reply: Reply<DeviceCryptoIdentity>) : SecurityRequest
private data class ReconcileCommand(val id: String, val members: Set<String>, val reply: Reply<ConversationSecurityState>) : SecurityRequest
private data class CommitCommand(val value: DirectoryCommit, val reply: Reply<Unit>) : SecurityRequest
private data class WelcomeCommand(val value: DirectoryWelcome, val reply: Reply<Unit>) : SecurityRequest
private data class SealCommand(val id: String, val envelope: ByteArray, val body: ByteArray, val circle: Boolean, val anonymous: Boolean, val reply: Reply<SealedMessage>) : SecurityRequest
private data class OpenCommand(val id: String, val envelope: ByteArray, val sealed: SealedMessage, val circle: Boolean, val reply: Reply<OpenedMessage>) : SecurityRequest
private data class KeyRingCommand(val protection: PlatformSecretBox?, val reply: Reply<SealedSecret>) : SecurityRequest
private data object SettleOpened : SecurityCommand

private class Opening(val command: OpenCommand, val opened: OpenedMessage)

private fun OpenedMessage.wipe() {
    plaintext.fill(0)
    frankingKey.fill(0)
}

@Serializable
private data class PendingSecurityCommit(val publication: CommitPublication, val members: Set<String>)

@Serializable
private data class SecuritySavedState(
    val deviceId: String,
    val snapshot: ByteArray,
    val credential: ByteArray? = null,
    val packages: List<PublishedKeyPackage> = emptyList(),
    val lastResortPublished: Boolean = false,
    val pending: Map<String, PendingSecurityCommit> = emptyMap(),
    val welcomeAcks: Set<String> = emptySet(),
    val rotations: Map<String, Long> = emptyMap(),
    val joinedEpochs: Map<String, Long> = emptyMap(),
)

class SecurityNode(graph: Graph, private val telemetry: SpanSink? = null) : ActorNode<SecurityCommand>(graph, actorKey("security/device")) {
    private val session by consumes<SecuritySession>()
    private val secretBox by consumes<PlatformSecretBox>()
    private val store by consumes<ObjectStore>(name = "security-state")
    private val directory = SecureDirectoryApi()
    private val register by consumes(directory.register)
    private val publishPackages by consumes(directory.publishPackages)
    private val devices by consumes(directory.devices)
    private val claimPackages by consumes(directory.claimPackages)
    private val publishCommit by consumes(directory.publishCommit)
    private val commits by consumes(directory.commits)
    private val groupInfo by consumes(directory.groupInfo)
    private val welcomes by consumes(directory.welcomes)
    private val consumeWelcomes by consumes(directory.consumeWelcomes)
    private val states = MutableStateFlow<Map<String, ConversationSecurityState>>(emptyMap())
    private var engine: SecurityEngine? = null
    private var saved: SecuritySavedState? = null
    private var recordId = ""
    private var recordAad = byteArrayOf()
    private var policy: EnginePolicy? = null
    private val opening = mutableListOf<Opening>()
    private var settling = false

    val security by provides<ReaktorSecurity>(object : ReaktorSecurity {
        override suspend fun device(): DeviceCryptoIdentity = ask { DeviceCommand(it) }
        override suspend fun reconcile(conversationId: String, members: Set<String>): ConversationSecurityState =
            ask { ReconcileCommand(conversationId, members.toSet(), it) }
        override suspend fun receive(commit: DirectoryCommit) { ask<Unit> { CommitCommand(commit, it) } }
        override suspend fun receive(welcome: DirectoryWelcome) { ask<Unit> { WelcomeCommand(welcome, it) } }
        override fun state(conversationId: String): Flow<ConversationSecurityState> =
            states.map { it[conversationId] ?: ConversationSecurityState(conversationId) }.distinctUntilChanged()
        override suspend fun seal(conversationId: String, envelope: ByteArray, plaintext: ByteArray, circleGame: Boolean, anonymous: Boolean): SealedMessage =
            ask { SealCommand(conversationId, envelope.copyOf(), plaintext.copyOf(), circleGame, anonymous, it) }
        override suspend fun open(conversationId: String, envelope: ByteArray, sealed: SealedMessage, circleGame: Boolean): OpenedMessage =
            ask { OpenCommand(conversationId, envelope.copyOf(), sealed.copy(header = sealed.header.copyOf(), commitment = sealed.commitment.copyOf(), ciphertext = sealed.ciphertext.copyOf()), circleGame, it) }
        override suspend fun sealedKeyRing(protection: PlatformSecretBox?): SealedSecret = ask { KeyRingCommand(protection, it) }
    })

    override suspend fun receive(message: SecurityCommand) = when (message) {
        SettleOpened -> observed("open.settle") { settle() }
        is SecurityRequest -> observed(when (message) {
            is DeviceCommand -> "enrol"
            is ReconcileCommand -> "reconcile"
            is CommitCommand -> "commit.receive"
            is WelcomeCommand -> "join"
            is SealCommand -> "seal"
            is OpenCommand -> "open"
            is KeyRingCommand -> "key-ring"
        }) { handle(message) }
    }

    private suspend fun handle(message: SecurityRequest) {
        val readState = if (message is OpenCommand) states.value[message.id] else null
        var authenticatingMessage = false
        try {
            initialize()
            if (current().credential == null || message !is OpenCommand && message !is KeyRingCommand) ensureDevice()
            when (message) {
                is DeviceCommand -> message.reply.complete(identity())
                is ReconcileCommand -> message.reply.complete(reconcile(message.id, message.members))
                is CommitCommand -> { reconcile(message.value.conversationId, emptySet()); message.reply.complete(Unit) }
                is WelcomeCommand -> {
                    require(message.value.deviceId == current().deviceId)
                    reconcile(message.value.conversationId, emptySet())
                    message.reply.complete(Unit)
                }
                is SealCommand -> {
                    reconcile(message.id, emptySet())
                    val result = crypto().seal(message.id, message.envelope, message.body, message.circle, message.anonymous)
                    persist()
                    message.reply.complete(SealedMessage(crypto().epoch(message.id), current().deviceId, result.header, result.commitment, result.ciphertext))
                }
                is OpenCommand -> {
                    if (epoch(message.id)?.let { it >= message.sealed.epoch } != true) {
                        retryPending(message.id)
                        catchUp(message.id)
                    }
                    authenticatingMessage = true
                    val opened = decrypt(message)
                    authenticatingMessage = false
                    hold(Opening(message, opened))
                }
                is KeyRingCommand -> {
                    val bytes = crypto().exportKeyRing()
                    try { message.reply.complete((message.protection ?: secretBox()).seal(SecretRecordType.MlsGroupStateSnapshot, recordId + ":key-ring", bytes, recordAad)) }
                    finally { bytes.fill(0) }
                }
            }
        } catch (error: Throwable) {
            var restored = true
            try { restoreDurable() } catch (restoreError: Throwable) { restored = false; error.addSuppressed(restoreError) }
            val id = when (message) {
                is ReconcileCommand -> message.id
                is CommitCommand -> message.value.conversationId
                is WelcomeCommand -> message.value.conversationId
                is SealCommand -> message.id
                is OpenCommand -> message.id
                else -> null
            }
            if (id != null) runCatching {
                val health = if (authenticatingMessage && restored && readState?.epoch != null && readState.epoch == epoch(id))
                    readState.health else ConversationHealth.Failed
                update(id, health)
            }
            throw error
        } finally {
            if (message is SealCommand) message.body.fill(0)
        }
    }

    private suspend fun <T> observed(operation: String, outcome: (T) -> Int = { 200 }, block: suspend () -> T): T {
        val sink = telemetry ?: return block()
        val parent = currentCoroutineContext()[ServiceCall]
        val trace = parent?.trace?.child() ?: TraceContext.root()
        val started = Clock.System.now().toEpochMilliseconds()
        val elapsed = TimeSource.Monotonic.markNow()
        var status = 200
        var cause = ""
        return try {
            withContext(ServiceCall(trace, parent?.trace?.spanId, parent?.attributes.orEmpty())) { block() }.also { status = outcome(it) }
        } catch (error: Throwable) {
            status = when (error) {
                is CancellationException -> 499
                is CallFailure -> error.status
                else -> 500
            }
            cause = when (error) {
                is CancellationException -> "cancelled"
                is NativeSecurityException -> "native_${error.status}"
                is CallFailure -> "directory_${error.status}"
                else -> error::class.simpleName ?: "unknown"
            }
            throw error
        } finally {
            runCatching {
                sink.record(ServiceSpan(trace.traceId, trace.spanId, parent?.trace?.spanId.orEmpty(),
                    ServiceExecutionPhase.CLIENT, "reaktor-security", "reaktor.security", operation, started,
                    elapsed.elapsedNow().inWholeMicroseconds / 1000.0, status, node = id.toString(),
                    decisions = if (cause.isEmpty()) emptyMap() else mapOf("failure" to cause)))
            }
        }
    }

    private fun current() = checkNotNull(saved)
    private fun crypto() = checkNotNull(engine)
    private fun now() = Clock.System.now().toEpochMilliseconds()

    private suspend fun initialize() {
        if (engine != null) return
        val principal = session()
        policy = EnginePolicy(principal.tenantId, principal.appId, principal.authKeys.mapValues { it.value.copyOf() })
        recordId = json.encodeToString(listOf(principal.tenantId, principal.appId, principal.userId))
        recordAad = ("reaktor/security/device/v1:" + recordId).encodeToByteArray()
        if (principal.resetIdentity) store().delete(recordId)
        val sealed = store().read<SealedSecret>(recordId)?.value
        if (sealed == null) {
            engine = SecurityEngineFactory.create(checkNotNull(policy))
            persist(SecuritySavedState(Uuid.random().toString(), byteArrayOf()))
        } else {
            val bytes = secretBox().open(SecretRecordType.MlsDeviceIdentity, recordId, sealed, recordAad)
            val state = try { json.decodeFromString<SecuritySavedState>(bytes.decodeToString()) } finally { bytes.fill(0) }
            try { engine = SecurityEngineFactory.restore(state.snapshot, checkNotNull(policy)); saved = state }
            catch (error: Throwable) { state.snapshot.fill(0); throw error }
        }
    }

    private suspend fun persist(next: SecuritySavedState = current()) {
        val snapshot = observed("state.snapshot") { crypto().snapshot() }
        if (next == saved && snapshot.contentEquals(next.snapshot)) {
            snapshot.fill(0)
            release()
            return
        }
        val state = next.copy(snapshot = snapshot)
        val bytes = observed("state.encode") { json.encodeToString(state).encodeToByteArray() }
        try {
            require(bytes.size <= 64 * 1024 * 1024)
            val sealed = observed("state.protect") {
                secretBox().seal(SecretRecordType.MlsDeviceIdentity, recordId, bytes, recordAad)
            }
            observed("state.write") { store().write(recordId, sealed) }
            saved?.snapshot?.fill(0)
            saved = state
            release()
        } catch (error: Throwable) {
            state.snapshot.fill(0)
            throw error
        } finally { bytes.fill(0) }
    }

    private fun restoreDurable() {
        val held = opening.toList()
        opening.clear()
        held.forEach { it.opened.wipe() }
        engine?.close()
        engine = null
        try {
            saved?.let { engine = SecurityEngineFactory.restore(it.snapshot, checkNotNull(policy)) }
        } catch (error: Throwable) {
            held.forEach { it.command.reply.fail(error) }
            throw error
        }
        held.map { it.command }.forEach { command ->
            runCatching { decrypt(command) }
                .onSuccess { opening += Opening(command, it) }
                .onFailure { command.reply.fail(it) }
        }
    }

    private fun decrypt(message: OpenCommand): OpenedMessage {
        val opened = crypto().open(message.id, message.envelope,
            EngineSealed(message.sealed.header, message.sealed.commitment, message.sealed.ciphertext), message.circle)
        try {
            val sender = opened.credential.takeIf { it.isNotEmpty() }?.let(::readDeviceCredential)
            check(!opened.authenticated || sender?.deviceId?.value == message.sealed.senderDeviceId)
            return OpenedMessage(opened.plaintext, opened.frankingKey, sender, opened.authenticated, opened.duplicate)
        } catch (error: Throwable) {
            opened.plaintext.fill(0)
            opened.frankingKey.fill(0)
            throw error
        }
    }

    private suspend fun hold(held: Opening) {
        opening += held
        if (!settling) settling = trySend(SettleOpened)
        if (!settling) settle()
    }

    private suspend fun settle() {
        settling = false
        if (opening.isEmpty()) return
        try { persist() }
        catch (error: Throwable) {
            val held = opening.toList()
            opening.clear()
            held.forEach { it.opened.wipe() }
            try { restoreDurable() } catch (restoreError: Throwable) { error.addSuppressed(restoreError) }
            held.map { it.command.id }.distinct().forEach { runCatching { update(it, ConversationHealth.Failed) } }
            held.forEach { it.command.reply.fail(error) }
            throw error
        }
    }

    private fun release() {
        opening.forEach { if (!it.command.reply.complete(it.opened)) it.opened.wipe() }
        opening.clear()
    }

    private suspend fun ensureDevice() {
        if (current().credential == null) {
            val principal = session()
            val id = current().deviceId
            val registration = register(DeviceRegistration(id, crypto().publicKey(), 3, crypto().proveDevice(principal.userId, id)))
            check(registration.deviceId == id && registration.userId == principal.userId && registration.signatureKey.contentEquals(crypto().publicKey()))
            crypto().installCredential(registration.credential)
            persist(current().copy(credential = registration.credential.copyOf()))
        }
        if (current().packages.isNotEmpty()) flushPackages()
        val count = publishPackages(KeyPackagePublication(current().deviceId, emptyList()))
        if (count < 21) {
            val id = current().deviceId
            val reserve = if (current().lastResortPublished) emptyList() else
                listOf(PublishedKeyPackage(id + ":last-resort", crypto().lastResortKeyPackage(), true))
            val packages = List(maxOf(0, 21 - count - reserve.size)) {
                PublishedKeyPackage(id + ":" + Uuid.random(), crypto().keyPackage())
            } + reserve
            persist(current().copy(packages = packages))
            flushPackages()
        }
        acknowledgeWelcomes()
    }

    private suspend fun flushPackages() {
        publishPackages(KeyPackagePublication(current().deviceId, current().packages))
        persist(current().copy(packages = emptyList(), lastResortPublished = current().lastResortPublished || current().packages.any { it.lastResort }))
    }

    private fun identity(): DeviceCryptoIdentity {
        val bytes = checkNotNull(current().credential).copyOf()
        val credential = readDeviceCredential(bytes)
        return DeviceCryptoIdentity(credential, bytes, credential.mlsSignaturePublicKey.fingerprint())
    }

    private fun epoch(id: String): Long? = try { crypto().epoch(id) }
    catch (error: NativeSecurityException) { if (error.status == 7) null else throw error }

    private suspend fun retryPending(id: String): Boolean {
        val pending = current().pending[id] ?: return true
        crypto().setMembers(id, pending.members)
        val accepted = try { observed("commit", { if (it.accepted) 200 else 409 }) { publishCommit(pending.publication) } }
        catch (error: Throwable) {
            val failure = error.asCallFailure()
            if (failure is Rejected && failure.status == 409 && pending.publication.previousEpoch == null) {
                val info = groupInfo(id)
                if (info.epoch == null && !info.activationReady) {
                    crypto().discard(id)
                    persist(current().copy(pending = current().pending - id))
                    return false
                }
            }
            if (failure is Rejected && failure.status == 400) {
                crypto().discard(id)
                persist(current().copy(pending = current().pending - id))
                return false
            }
            throw error
        }
        if (accepted.accepted) {
            check(accepted.epoch == pending.publication.epoch)
            val joining = epoch(id) == null
            crypto().accept(id, pending.publication.commit)
            persist(current().copy(pending = current().pending - id, rotations = current().rotations + (id to now()),
                joinedEpochs = if (joining) current().joinedEpochs + (id to accepted.epoch) else current().joinedEpochs))
        } else {
            crypto().discard(id)
            persist(current().copy(pending = current().pending - id))
        }
        return accepted.accepted
    }

    private suspend fun publish(id: String, result: EngineCommit, added: Set<String>, removed: Set<String>, members: Set<String>) {
        val publication = CommitPublication(id, current().deviceId, (result.epoch - 1).takeIf { it > 0 }, result.epoch, result.commit, result.groupInfo,
            added, removed, (added - current().deviceId).map { device ->
                DirectoryWelcome(Uuid.random().toString(), device, id, result.epoch, result.welcome)
            })
        persist(current().copy(pending = current().pending + (id to PendingSecurityCommit(publication, members))))
        retryPending(id)
    }

    private suspend fun joinWelcome(id: String) {
        for (welcome in welcomes(current().deviceId).filter { it.conversationId == id }.sortedBy { it.epoch }) {
            if (epoch(id) == null) {
                require(welcome.members.isNotEmpty())
                crypto().setMembers(id, welcome.members)
                crypto().join(id, welcome.bytes)
                check(crypto().epoch(id) == welcome.epoch)
                persist(current().copy(rotations = current().rotations + (id to now()),
                    joinedEpochs = current().joinedEpochs + (id to welcome.epoch)))
            }
            persist(current().copy(welcomeAcks = current().welcomeAcks + welcome.id))
            acknowledgeWelcomes()
        }
    }

    private suspend fun acknowledgeWelcomes() {
        while (current().welcomeAcks.isNotEmpty()) {
            val ids = current().welcomeAcks.take(512).toSet()
            consumeWelcomes(WelcomeConsumption(current().deviceId, ids))
            persist(current().copy(welcomeAcks = current().welcomeAcks - ids))
        }
    }

    private suspend fun catchUp(id: String) {
        if (epoch(id) == null) joinWelcome(id)
        var after = epoch(id) ?: return
        while (true) {
            val batch = commits(CommitCursor(id, after))
            if (batch.isEmpty()) return
            for (commit in batch) {
                require(commit.conversationId == id && commit.epoch == after + 1 && commit.members.isNotEmpty() && commit.roster.isNotEmpty())
                crypto().setMembers(id, commit.members)
                check(crypto().process(id, commit.bytes) && crypto().epoch(id) == commit.epoch)
                check(crypto().roster(id).map { readDeviceCredential(it).deviceId.value }.toSet() == commit.roster)
                persist()
                after = commit.epoch
            }
            if (batch.size < 512) return
        }
    }

    private suspend fun reconcile(id: String, requestedMembers: Set<String>): ConversationSecurityState {
        require(id.isNotBlank() && id.length <= 256)
        update(id, ConversationHealth.Reconciling)
        repeat(8) {
            retryPending(id)
            catchUp(id)
            val info = groupInfo(id)
            if (epoch(id) == null && info.epoch == null && !info.activationReady)
                return update(id, ConversationHealth.Waiting)
            val desired = devices(DirectoryLookup(id, emptySet()))
            val desiredIds = desired.map { it.deviceId }.toSet()
            val members = desired.map { it.userId }.toSet()
            require(desiredIds.size == desired.size && current().deviceId in desiredIds)
            require(requestedMembers.isEmpty() || requestedMembers.containsAll(members))
            val localEpoch = epoch(id)
            if (localEpoch == null) {
                if (info.epoch == null) {
                    crypto().setMembers(id, members)
                    val packages = claimPackages(DirectoryLookup(id, emptySet())).filter { it.device.deviceId != current().deviceId }
                    check(packages.map { it.device.deviceId }.toSet() == desiredIds - current().deviceId)
                    val result = crypto().prepareCreate(id, packages.map { it.bytes })
                    publish(id, result, desiredIds, emptySet(), members)
                } else {
                    val historical = info.members + members
                    require(info.members.isNotEmpty() && info.bytes != null)
                    crypto().setMembers(id, historical)
                    val result = crypto().prepareExternalJoin(id, info.bytes)
                    check(result.epoch == info.epoch + 1)
                    publish(id, result, setOf(current().deviceId), emptySet(), historical)
                }
                return@repeat
            }
            if (localEpoch != info.epoch) return@repeat
            val roster = crypto().roster(id).map(::readDeviceCredential)
            val oldIds = roster.map { it.deviceId.value }.toSet()
            val missing = desiredIds - oldIds
            val removed = oldIds - desiredIds
            crypto().setMembers(id, members)
            if (missing.isNotEmpty() || removed.isNotEmpty()) {
                val packages = if (missing.isEmpty()) emptyList() else
                    claimPackages(DirectoryLookup(id, emptySet())).filter { it.device.deviceId in missing }
                check(packages.map { it.device.deviceId }.toSet() == missing)
                val indices = roster.mapIndexedNotNull { index, device -> index.takeIf { device.deviceId.value in removed } }
                val result = crypto().prepareMembership(id, packages.map { it.bytes }, indices)
                publish(id, result, missing, removed, members)
                return@repeat
            }
            if (now() - (current().rotations[id] ?: 0) >= 7L * 24 * 60 * 60 * 1000) {
                publish(id, crypto().prepareRotate(id), emptySet(), emptySet(), members)
                return@repeat
            }
            persist()
            return update(id, ConversationHealth.Ready)
        }
        error("Conversation changed repeatedly; retry reconciliation")
    }

    private fun update(id: String, health: ConversationHealth): ConversationSecurityState {
        val epoch = engine?.let { epoch(id) }
        val roster = if (epoch == null) emptyList() else crypto().roster(id).map(::readDeviceCredential).map {
            SecureRosterDevice(it.userId.value, it.deviceId.value, it.mlsSignaturePublicKey.fingerprint())
        }
        val value = ConversationSecurityState(id, epoch, roster, health, saved?.joinedEpochs?.get(id))
        states.value = states.value + (id to value)
        return value
    }

    override suspend fun postStop() {
        runCatching { settle() }
        engine?.close()
        engine = null
        saved?.snapshot?.fill(0)
        saved = null
    }
}

