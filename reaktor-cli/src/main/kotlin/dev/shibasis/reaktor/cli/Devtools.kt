package dev.shibasis.reaktor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import dev.shibasis.reaktor.devtools.AgentAttachment
import dev.shibasis.reaktor.devtools.AgentCommandResult
import dev.shibasis.reaktor.devtools.AgentDescriptor
import dev.shibasis.reaktor.devtools.AgentFact
import dev.shibasis.reaktor.devtools.DevToolsProtocol
import dev.shibasis.reaktor.tooling.DevelopmentDevice
import dev.shibasis.reaktor.tooling.DeviceInspection
import dev.shibasis.reaktor.tooling.DeviceTransport
import dev.shibasis.reaktor.tooling.DeviceViewSnapshot
import dev.shibasis.reaktor.tooling.device.AdbDeviceBackend
import dev.shibasis.reaktor.tooling.device.AdbDeviceSession
import dev.shibasis.reaktor.tooling.device.AppleDeviceBackend
import dev.shibasis.reaktor.tooling.device.AppleDeviceSession
import dev.shibasis.reaktor.tooling.device.ContrastAudit
import dev.shibasis.reaktor.tooling.device.ContrastFinding
import dev.shibasis.reaktor.tooling.device.ContrastVerdict
import dev.shibasis.reaktor.tooling.device.DeviceBackend
import dev.shibasis.reaktor.tooling.device.DeviceInput
import dev.shibasis.reaktor.tooling.device.DeviceSession
import dev.shibasis.reaktor.tooling.device.InputKind
import dev.shibasis.reaktor.tooling.device.RemoteSocket
import dev.shibasis.reaktor.tooling.device.SimctlDeviceBackend
import java.io.File
import java.util.Base64
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray

private val DevtoolsJson = Json { prettyPrint = true; encodeDefaults = true }

class Devtools : CliktCommand("devtools") {
    override fun help(context: Context) =
        "Talk to the devtools agent inside a running dev build, on a phone, a simulator or this machine"

    override fun run() {}
}

class DevtoolsDevices : CliktCommand("devices") {
    private val env by requireObject<ReaktorEnv>()

    override fun help(context: Context) = "List the devices an agent could be reached on"

    override fun run() = runBlocking {
        withBackends { candidates ->
            if (candidates.isEmpty()) env.terminal.println("No devices are connected.")
            candidates.forEach { (_, device) -> env.terminal.println("${device.id}\t${device.transport}\t${device.state}\t${device.name}") }
        }
    }
}

class DevtoolsDescribe : CliktCommand("describe") {
    private val env by requireObject<ReaktorEnv>()
    private val device by option("--device", help = "android, ios, a device id or serial, or part of its name; optional when one device is connected")

    override fun help(context: Context) = "Print the agent's descriptor: the app, its build and every capability with its safety"

    override fun run() = runBlocking {
        withAgent(device) { link -> env.terminal.println(DevtoolsJson.encodeToString(AgentDescriptor.serializer(), link.descriptor)) }
    }
}

class DevtoolsRun : CliktCommand("run") {
    private val env by requireObject<ReaktorEnv>()
    private val device by option("--device", help = "android, ios, a device id or serial, or part of its name; optional when one device is connected")
    private val capability by argument(help = "capability name, for example world")
    private val action by argument(help = "action name, for example advance")
    private val arguments by argument(help = "key=value arguments, for example by=2h").multiple()

    override fun help(context: Context) = "Run one command on the agent, for example: reaktor devtools run world advance by=2h"

    override fun run() {
        val parsed = arguments.associate { pair ->
            if ('=' !in pair) throw UsageError("Arguments are key=value pairs; got '$pair'")
            pair.substringBefore('=') to pair.substringAfter('=')
        }
        val result = runBlocking { withAgent(device) { link -> link.attachment.execute(capability, action, parsed) } }
        env.terminal.println(DevtoolsJson.encodeToString(AgentCommandResult.serializer(), result))
        if (!result.accepted) throw ProgramResult(1)
    }
}

class DevtoolsSemantics : CliktCommand("semantics") {
    private val env by requireObject<ReaktorEnv>()
    private val device by option("--device", help = "android, ios, a device id or serial, or part of its name; optional when one device is connected")

    override fun help(context: Context) = "Print the element tree the app reports: role, text, test tag and bounds of each node"

    override fun run() {
        val tree = runBlocking { withAgent(device) { link -> link.attachment.semantics() } }
        tree.nodes.forEach { node ->
            val label = listOf(node.role, node.testTag.takeIf(String::isNotEmpty)?.let { "#$it" }, node.text.takeIf(String::isNotEmpty)?.let { "\"$it\"" })
                .filter { !it.isNullOrEmpty() }.joinToString(" ")
            env.terminal.println("${"  ".repeat(node.depth)}${label.ifEmpty { node.id }} [${node.left.toInt()},${node.top.toInt()} ${node.right.toInt()},${node.bottom.toInt()}]")
        }
        if (tree.truncated) env.terminal.println("(truncated)")
    }
}

class DevtoolsContrast : CliktCommand("contrast") {
    private val env by requireObject<ReaktorEnv>()
    private val device by option("--device", help = "android, ios, a device id or serial, or part of its name; optional when one device is connected")
    private val out by option("--out", help = "also save the screenshot the audit read")
    private val app by option("--app", help = "the app to measure: its package on Android, its name or bundle id on an iPhone; nothing outside it is read").required()

    override fun help(context: Context) =
        "Measure the contrast of every piece of text an app draws, from the accessibility tree and a screenshot"

    override fun run() {
        val findings = runBlocking {
            withSession(device) { session ->
                val tree = when (session) {
                    is AdbDeviceSession -> DeviceInspection.viewTree(DeviceTransport.Adb, session.shell("uiautomator dump /data/local/tmp/reaktor-view.xml >/dev/null && cat /data/local/tmp/reaktor-view.xml && rm /data/local/tmp/reaktor-view.xml"))
                        .let { tree -> DeviceViewSnapshot(tree.elements.filter { it.attributes["package"] == app }) }
                    is AppleDeviceSession -> DeviceInspection.viewTree(DeviceTransport.Idb, session.accessibilityTree()).also { tree ->
                        val front = tree.elements.firstOrNull { it.attributes["type"] == "Application" }?.text
                        if (front != app) throw CliktError("$app is not in front${front?.let { " ($it is)" }.orEmpty()}; nothing was measured")
                    }
                    else -> throw CliktError("Contrast needs a phone; ${session.device.name} has no accessibility tree to read")
                }
                val shot = session.screenshot()
                out?.let { File(it).writeBytes(shot) }
                ContrastAudit.audit(shot, tree.elements)
            }
        }
        findings.forEach { report(it) }
        val failing = findings.count { it.verdict == ContrastVerdict.Fail }
        val large = findings.count { it.verdict == ContrastVerdict.LargeTextOnly }
        env.terminal.println("${findings.size} texts · $failing under 3:1 · $large between 3:1 and 4.5:1, which passes only for large text")
        if (failing > 0) throw ProgramResult(1)
    }

    private fun report(finding: ContrastFinding) {
        val mark = when (finding.verdict) {
            ContrastVerdict.Pass -> "ok  "
            ContrastVerdict.LargeTextOnly -> "LARGE"
            ContrastVerdict.Fail -> "FAIL"
        }
        env.terminal.println("$mark ${"%.2f".format(finding.ratio)}:1  #${"%06X".format(finding.foreground)} on #${"%06X".format(finding.background)}  \"${finding.text.take(60)}\"")
    }
}

class DevtoolsLogs : CliktCommand("logs") {
    private val env by requireObject<ReaktorEnv>()
    private val device by option("--device", help = "android, ios, a device id or serial, or part of its name; optional when one device is connected")
    private val limit by option("--limit", help = "how many of the newest entries").int().default(200)

    override fun help(context: Context) = "Print the app's recent log entries, as the agent inside it recorded them"

    override fun run() {
        val page = runBlocking { withAgent(device) { link -> link.attachment.logs(limit = limit) } }
        page.entries.forEach { entry ->
            env.terminal.println("${entry.level.name.first()} ${entry.subsystem}: ${entry.message}${entry.throwable?.let { "\n    $it" }.orEmpty()}")
        }
        if (page.droppedSinceCursor > 0) env.terminal.println("(${page.droppedSinceCursor} older entries were dropped)")
    }
}

class DevtoolsWatch : CliktCommand("watch") {
    private val env by requireObject<ReaktorEnv>()
    private val device by option("--device", help = "android, ios, a device id or serial, or part of its name; optional when one device is connected")
    private val capability by argument(help = "the stream to follow, for example traffic, logs or port.events")
    private val seconds by option("--seconds", help = "how long to follow it").int().default(15)

    override fun help(context: Context) = "Follow one of the agent's streams for a while and print each fact as it arrives"

    override fun run() = runBlocking {
        withAgent(device) { link ->
            coroutineScope {
                val printing = launch {
                    link.attachment.facts.collect { (stream, fact) ->
                        if (stream == capability) env.terminal.println(DevtoolsJson.encodeToString(AgentFact.serializer(), fact).replace(Regex("\\s+"), " "))
                    }
                }
                link.attachment.subscribe(capability)
                delay(seconds * 1000L)
                link.attachment.unsubscribe(capability)
                printing.cancel()
            }
        }
    }
}

class DevtoolsTap : CliktCommand("tap") {
    private val env by requireObject<ReaktorEnv>()
    private val device by option("--device", help = "android, ios, a device id or serial, or part of its name; optional when one device is connected")
    private val x by argument(help = "x, in the platform's own units: pixels on Android, points on an iPhone").int()
    private val y by argument(help = "y, in the same units").int()

    override fun help(context: Context) = "Tap the screen through the device layer, without a test runner"

    override fun run() {
        runBlocking { withSession(device) { session -> session.input(DeviceInput(InputKind.Tap, x, y)) } }
        env.terminal.println("Tapped $x,$y")
    }
}

class DevtoolsScreenshot : CliktCommand("screenshot") {
    private val env by requireObject<ReaktorEnv>()
    private val device by option("--device", help = "android, ios, a device id or serial, or part of its name; optional when one device is connected")
    private val out by option("--out", help = "where to write the PNG").default("screenshot.png")

    override fun help(context: Context) = "Save what the app is drawing, as the agent inside it sees it"

    override fun run() {
        val shot = runBlocking { withAgent(device) { link -> link.attachment.screenshot() } }
        if (shot.pngBase64.isEmpty()) throw CliktError(shot.unavailableReason ?: "The agent returned no image")
        File(out).writeBytes(Base64.getDecoder().decode(shot.pngBase64))
        env.terminal.println("${shot.widthPixels}x${shot.heightPixels} -> $out")
    }
}

private fun platformNames(transport: DeviceTransport) = when (transport) {
    DeviceTransport.Adb -> setOf("android")
    DeviceTransport.Idb, DeviceTransport.Simctl -> setOf("ios", "iphone")
}

private suspend fun <T> withBackends(block: suspend (List<Pair<DeviceBackend, DevelopmentDevice>>) -> T): T {
    Logger.getLogger("").handlers.forEach { it.level = Level.WARNING }
    val backends = listOf(AdbDeviceBackend(), AppleDeviceBackend(), SimctlDeviceBackend())
    try {
        val candidates = backends.filter { it.unavailableReason() == null }.flatMap { backend ->
            runCatching { backend.list() }.getOrDefault(emptyList())
                .filter { it.ready }
                .map { backend to it }
        }
        return block(candidates)
    } finally {
        backends.forEach { runCatching { it.close() } }
    }
}

private suspend fun <T> withSession(selector: String?, block: suspend (DeviceSession) -> T): T = withBackends { candidates ->
    val (backend, device) = pick(candidates, selector)
    backend.session(device).use { session -> block(session) }
}

private fun pick(candidates: List<Pair<DeviceBackend, DevelopmentDevice>>, selector: String?): Pair<DeviceBackend, DevelopmentDevice> {
    val matching = if (selector == null) candidates else candidates.filter { (_, device) ->
        device.id.equals(selector, ignoreCase = true) || device.name.contains(selector, ignoreCase = true) ||
            selector.lowercase() in platformNames(device.transport)
    }
    return when (matching.size) {
        1 -> matching.single()
        0 -> throw CliktError(if (selector == null) "No devices are connected." else "No connected device matches '$selector'.")
        else -> throw CliktError("Several devices are connected; pick one with --device:\n" +
            matching.joinToString("\n") { (_, device) -> "  ${device.id}  ${device.name}" })
    }
}

private suspend fun <T> withAgent(selector: String?, block: suspend (DeviceAgentLink) -> T): T = withBackends { candidates ->
    val (backend, device) = pick(candidates, selector)
    backend.session(device).use { session ->
        coroutineScope {
            val link = session.attachAgent(this)
            try {
                block(link)
            } finally {
                link.close()
            }
        }
    }
}

class DeviceAgentLink(
    val attachment: AgentAttachment,
    val descriptor: AgentDescriptor,
    private val release: suspend () -> Unit,
) {
    suspend fun close() {
        attachment.detach()
        release()
    }
}

suspend fun DeviceSession.attachAgent(scope: CoroutineScope): DeviceAgentLink {
    val remote = if (this is AdbDeviceSession) RemoteSocket.LocalAbstract(DevToolsProtocol.AndroidSocketName)
    else RemoteSocket.Tcp(DevToolsProtocol.DefaultLoopbackPort)
    val port = forward(0, remote)
    val attachment = AgentAttachment("127.0.0.1", port, scope)
    val descriptor = runCatching { attachment.attach() }.getOrElse { failure ->
        attachment.detach()
        removeForward(port)
        throw IllegalStateException("No devtools agent answered on ${device.name}. Is a dev-tools build of the app running? (${failure.message})", failure)
    }
    return DeviceAgentLink(attachment, descriptor) { removeForward(port) }
}
