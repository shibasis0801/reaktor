package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class HostedWorkspace(val root: String, val plus: Boolean = false, val connector: Boolean = false)

@Serializable
data class HostedState(val root: String, val thread: String, val serving: Boolean, val failure: String? = null)

@Serializable
internal data class HostRequest(val action: String, val root: String? = null)

@Serializable
internal data class HostReply(val ok: Boolean, val failure: String? = null, val workspaces: List<HostedState> = emptyList(), val processId: Long = 0)

object AgentServiceHost {
    const val Label = "build.reaktor.kernel"
    private const val OpenMillis = 5 * 60 * 1000L

    fun directory(home: File = File(System.getProperty("user.home"))): Path = home.toPath().resolve(".reaktor").resolve("kernel")

    fun hosted(directory: Path = directory()): List<HostedWorkspace> = directory.resolve("workspaces.json").let { file ->
        if (!Files.isRegularFile(file)) emptyList()
        else ConductorJson.decodeFromString(ListSerializer(HostedWorkspace.serializer()), Files.readString(file))
    }

    fun hosts(root: File, directory: Path = directory()): Boolean = hosted(directory).any { it.root == root.canonicalPath }

    fun states(directory: Path = directory()): List<HostedState>? = runCatching { ask(HostRequest("list"), directory).workspaces }.getOrNull()

    @Synchronized fun connect(root: File, command: List<String>, plus: Boolean = false, directory: Path = directory()): AgentWorkspaceConnection {
        AgentWorkspaceRoots.refusal(root)?.let { throw IllegalArgumentException(it) }
        val workspace = HostedWorkspace(root.canonicalPath, plus)
        if (!plus || workspace in hosted(directory)) runCatching { AgentWorkspaceConnection.open(root, allowStart = false) }.getOrNull()?.let { return it }
        admit(workspace, directory)
        start(command, directory)
        ask(HostRequest("open", workspace.root), directory, OpenMillis).failure?.let { error("The kernel could not open ${root.path}: $it") }
        return AgentWorkspaceConnection.open(root, allowStart = false)
    }

    suspend fun move(root: File, command: List<String>, directory: Path = directory()): AgentWorkspaceConnection {
        AgentWorkspaceRoots.refusal(root)?.let { throw IllegalArgumentException(it) }
        val legacy = launchAgents().resolve("${AgentBackgroundService.label(root)}.plist")
        runCatching { AgentWorkspaceConnection.open(root, allowStart = false) }.getOrNull()?.let { owner ->
            val processId = try { requireNotNull(owner.info().service) { "The workspace owner did not report its process" }.processId }
                catch (failure: Exception) { owner.close(); throw failure }
            if (processId == hostProcessId(directory)) { retire(root); return owner }
            try {
                drain(listOf(owner))
                try {
                    AgentBackgroundService.stop(root)
                    withTimeout(30000) { while (alive(processId)) delay(100) }
                } catch (failure: Exception) {
                    runCatching { activeRuns(owner, drain = false) }
                    throw failure
                }
            } finally { owner.close() }
        }
        val connection = try { connect(root, command, directory = directory) } catch (failure: Exception) {
            if (Files.exists(legacy)) AgentBackgroundService.run(listOf("/bin/launchctl", "bootstrap", domain(), legacy.toString()))
            throw failure
        }
        retire(root)
        return connection
    }

    suspend fun upgrade(command: List<String>, directory: Path = directory()): List<String> {
        val workspaces = hosted(directory)
        val owners = workspaces.mapNotNull { runCatching { AgentWorkspaceConnection.open(File(it.root), allowStart = false) }.getOrNull() }
        try {
            val processId = hostProcessId(directory)
            drain(owners)
            try {
                stop()
                if (processId > 0) withTimeout(60000) { while (alive(processId)) delay(100) }
                Files.deleteIfExists(launchAgents().resolve("$Label.plist"))
                start(command, directory)
                workspaces.forEach { workspace ->
                    ask(HostRequest("open", workspace.root), directory, OpenMillis).failure?.let { error("The kernel could not reopen ${workspace.root}: $it") }
                }
            } catch (failure: Exception) {
                owners.forEach { runCatching { activeRuns(it, drain = false) } }
                throw failure
            }
        } finally { owners.forEach { it.close() } }
        return workspaces.map { it.root }
    }

    fun leave(root: File, directory: Path = directory()): String {
        edit(directory) { current -> current.filterNot { it.root == root.canonicalPath } }
        if (reachable(directory)) ask(HostRequest("close", root.canonicalPath), directory, 60000)
        return "The kernel no longer hosts ${root.path}. Unfinished tasks remain checkpointed for its next start."
    }

    fun stop(): String {
        val result = AgentBackgroundService.run(listOf("/bin/launchctl", "bootout", "${domain()}/$Label"))
        check(result.first == 0) { "Could not stop the kernel host: ${result.second.take(500)}" }
        return "Kernel host stopped. Every hosted workspace keeps its checkpointed tasks for the next start."
    }

    fun describe(home: File, loaded: Boolean): String? {
        if (!File(home, "Library/LaunchAgents/$Label.plist").isFile) return null
        val workspaces = hosted(directory(home))
        return "$Label  kernel host, ${if (loaded) "loaded" else "not loaded"}  ${workspaces.size} " +
            "${if (workspaces.size == 1) "workspace" else "workspaces"}${workspaces.joinToString("") { "\n  ${it.root}" }}"
    }

    fun serve(directory: Path = directory(), show: (() -> Unit)? = null, open: (HostedWorkspace) -> AutoCloseable?) {
        privateDirectory(directory)
        FileChannel.open(directory.resolve("host.lock"), CREATE, WRITE).use { channel ->
            val lock = channel.tryLock() ?: return println("Another kernel host already serves ${directory}")
            lock.use {
                AgentServiceHostProcess(directory, open, show).use { host ->
                    Runtime.getRuntime().addShutdownHook(Thread(host::close, "kernel/shutdown"))
                    host.run()
                }
            }
        }
    }

    fun show(directory: Path = directory()): Boolean = runCatching { ask(HostRequest("show"), directory, patienceMillis = 3000).ok }.getOrDefault(false)

    internal fun ask(request: HostRequest, directory: Path, patienceMillis: Long = 10000): HostReply =
        SocketChannel.open(UnixDomainSocketAddress.of(directory.resolve("control.sock"))).use { channel ->
            Channels.newWriter(channel, Charsets.UTF_8).apply { write(ConductorJson.encodeToString(HostRequest.serializer(), request) + "\n"); flush() }
            val line = CompletableFuture.supplyAsync { Channels.newReader(channel, Charsets.UTF_8).buffered().readLine() }
                .get(patienceMillis, TimeUnit.MILLISECONDS) ?: error("The kernel host closed its control connection")
            ConductorJson.decodeFromString(HostReply.serializer(), line)
        }

    internal fun configuration(command: List<String>, directory: Path): String {
        require(command.isNotEmpty() && File(command.first()).isAbsolute)
        fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun string(value: String) = "<string>${xml(value)}</string>"
        val log = directory.resolve("kernel.log").toString()
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key>${string(Label)}
<key>ProgramArguments</key><array>${command.joinToString("") { string(it) }}</array>
<key>WorkingDirectory</key>${string(directory.toString())}
<key>KeepAlive</key><true/>
<key>RunAtLoad</key><true/>
<key>ThrottleInterval</key><integer>10</integer>
<key>ExitTimeOut</key><integer>60</integer>
<key>AbandonProcessGroup</key><false/>
<key>ProcessType</key><string>Standard</string>
<key>EnvironmentVariables</key><dict><key>REAKTOR_AGENT_SUPERVISOR</key><string>launchd</string><key>PATH</key>${string(agentServicePath())}</dict>
<key>StandardOutPath</key>${string(log)}
<key>StandardErrorPath</key>${string(log)}
</dict></plist>
"""
    }

    private fun start(command: List<String>, directory: Path) {
        if (reachable(directory)) return
        privateDirectory(directory)
        FileChannel.open(directory.resolve("install.lock"), CREATE, WRITE).use { channel -> channel.lock().use install@{
            if (reachable(directory)) return@install
            check(System.getProperty("os.name").lowercase().contains("mac")) { "The kernel host runs under launchd on macOS" }
            val plist = launchAgents().also { Files.createDirectories(it) }.resolve("$Label.plist")
            if (AgentBackgroundService.run(listOf("/bin/launchctl", "print", "${domain()}/$Label")).first == 0) return@install
            if (!Files.exists(plist)) configuration(snapshotServiceCommand(command, directory), directory).let { content ->
                atomicWrite(directory.resolve("launch-agent.plist"), content)
                atomicWrite(plist, content)
            }
            val result = AgentBackgroundService.run(listOf("/bin/launchctl", "bootstrap", domain(), plist.toString()))
            check(result.first == 0 || AgentBackgroundService.run(listOf("/bin/launchctl", "print", "${domain()}/$Label")).first == 0) {
                "Could not start the kernel host: ${result.second.take(500)}"
            }
        } }
        val readyBy = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
        while (!reachable(directory)) {
            check(System.nanoTime() < readyBy) { "The kernel host did not open its control socket. Inspect ${directory.resolve("kernel.log")}" }
            Thread.sleep(100)
        }
    }

    private fun reachable(directory: Path): Boolean = runCatching { ask(HostRequest("list"), directory).ok }.getOrDefault(false)

    private fun hostProcessId(directory: Path): Long = runCatching { ask(HostRequest("list"), directory).processId }.getOrDefault(0L)

    internal fun admit(workspace: HostedWorkspace, directory: Path) = edit(directory) { current -> current.filterNot { it.root == workspace.root } + workspace }

    private fun edit(directory: Path, change: (List<HostedWorkspace>) -> List<HostedWorkspace>) {
        privateDirectory(directory)
        FileChannel.open(directory.resolve("workspaces.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
            atomicWrite(directory.resolve("workspaces.json"), ConductorJson.encodeToString(ListSerializer(HostedWorkspace.serializer()), change(hosted(directory))))
        } }
    }

    private suspend fun drain(owners: List<AgentWorkspaceConnection>) = try {
        withTimeout(OpenMillis) { while (owners.sumOf { activeRuns(it, drain = true) } > 0) delay(1000) }
    } catch (failure: Exception) {
        owners.forEach { runCatching { activeRuns(it, drain = false) } }
        throw failure
    }

    private suspend fun activeRuns(owner: AgentWorkspaceConnection, drain: Boolean): Int =
        owner.call("agent_service_drain", buildJsonObject { put("enabled", JsonPrimitive(drain)) }).jsonObject.getValue("activeRuns").jsonPrimitive.int

    private fun retire(root: File) {
        Files.deleteIfExists(launchAgents().resolve("${AgentBackgroundService.label(root)}.plist"))
        Files.deleteIfExists(AgentWorkspaceConnection.defaultDirectory(root).resolve("launch-agent.plist"))
    }

    private fun alive(processId: Long) = ProcessHandle.of(processId).map { it.isAlive }.orElse(false)
    private fun domain() = "gui/${AgentBackgroundService.uid()}"
    private fun launchAgents(): Path = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents")
}

internal class AgentServiceHostProcess(
    private val directory: Path,
    private val open: (HostedWorkspace) -> AutoCloseable?,
    private val show: (() -> Unit)? = null,
) : AutoCloseable {
    private val socket = directory.resolve("control.sock")
    private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    private val workspaces = mutableMapOf<String, Workspace>()
    private val closed = AtomicBoolean()
    private val processId = ProcessHandle.current().pid()

    private inner class Workspace(val spec: HostedWorkspace) {
        val opened = CountDownLatch(1)
        val released = CountDownLatch(1)
        val finished = CountDownLatch(1)
        @Volatile var failure: String? = null
        val thread = Thread(::serve, "workspace/" + File(spec.root).name).apply { isDaemon = false }
        val live get() = failure == null && finished.count > 0

        private fun serve() {
            val handle = try { open(spec) ?: error("Another process already owns ${spec.root}") } catch (problem: Throwable) {
                failure = problem.message ?: problem.javaClass.name
                println("Could not open ${spec.root}: $failure")
                opened.countDown()
                finished.countDown()
                return
            }
            println("Serving ${spec.root} on ${Thread.currentThread().name}")
            opened.countDown()
            try { released.await() }
            finally {
                try { handle.close() } catch (problem: Throwable) { problem.printStackTrace() }
                finally { finished.countDown() }
            }
        }

        fun state() = HostedState(spec.root, thread.name, opened.count == 0L && live, failure)
    }

    init {
        Files.deleteIfExists(socket)
        server.bind(UnixDomainSocketAddress.of(socket))
        Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"))
    }

    fun run() {
        AgentServiceHost.hosted(directory).forEach(::begin)
        println("Kernel host $processId started with ${AgentServiceHost.hosted(directory).size} workspaces")
        while (!closed.get()) {
            val client = try { server.accept() } catch (_: ClosedChannelException) { break } catch (_: AsynchronousCloseException) { break }
            Thread({ answer(client) }, "kernel/control").apply { isDaemon = true }.start()
        }
    }

    @Synchronized private fun begin(spec: HostedWorkspace): Workspace {
        check(!closed.get()) { "The kernel host is stopping" }
        workspaces[spec.root]?.takeIf { it.spec == spec && it.live }?.let { return it }
        workspaces.remove(spec.root)?.let(::release)
        return Workspace(spec).also { workspaces[spec.root] = it; it.thread.start() }
    }

    private fun release(workspace: Workspace) {
        workspace.released.countDown()
        workspace.finished.await(50, TimeUnit.SECONDS)
    }

    private fun answer(client: SocketChannel) = client.use {
        val line = Channels.newReader(client, Charsets.UTF_8).buffered().readLine() ?: return@use
        val reply = runCatching { reply(ConductorJson.decodeFromString(HostRequest.serializer(), line)) }
            .getOrElse { HostReply(false, it.message ?: it.javaClass.name, processId = processId) }
        Channels.newWriter(client, Charsets.UTF_8).apply { write(ConductorJson.encodeToString(HostReply.serializer(), reply) + "\n"); flush() }
    }

    private fun reply(request: HostRequest): HostReply = when (request.action) {
        "list" -> HostReply(true, workspaces = synchronized(this) { workspaces.values.map { it.state() } }, processId = processId)
        "open" -> {
            val spec = AgentServiceHost.hosted(directory).firstOrNull { it.root == request.root }
                ?: return HostReply(false, "${request.root} is not on the kernel's workspace list", processId = processId)
            val workspace = begin(spec)
            if (!workspace.opened.await(5, TimeUnit.MINUTES)) HostReply(false, "${spec.root} did not open within five minutes", processId = processId)
            else HostReply(workspace.failure == null, workspace.failure, processId = processId)
        }
        "close" -> {
            synchronized(this) { workspaces.remove(request.root) }?.let(::release)
            HostReply(true, processId = processId)
        }
        "show" -> show?.let { it(); HostReply(true, processId = processId) } ?: HostReply(false, "This kernel host has no window", processId = processId)
        else -> HostReply(false, "Unknown kernel host request ${request.action}", processId = processId)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        val all = synchronized(this) { workspaces.values.toList().also { workspaces.clear() } }
        all.forEach { it.released.countDown() }
        all.forEach { it.finished.await(50, TimeUnit.SECONDS) }
        Files.deleteIfExists(socket)
    }
}
