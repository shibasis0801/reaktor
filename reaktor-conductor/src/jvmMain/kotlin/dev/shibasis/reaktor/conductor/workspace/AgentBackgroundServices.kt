package dev.shibasis.reaktor.conductor.workspace

import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

enum class BackgroundKind(val plain: String) { Owner("owner"), ConductorService("old conductor service"), Other("other service") }

enum class BackgroundProblem(private val one: String, private val many: String) {
    NoFolder("names no folder", "name no folder"),
    Missing("is for a folder that no longer exists", "are for folders that no longer exist"),
    Conductor("is an old conductor service", "are old conductor services"),
    Unsafe("serves /, the home folder or a temporary folder", "serve /, the home folder or a temporary folder");

    fun phrase(count: Int = 1) = if (count == 1) one else many
}

data class BackgroundAgent(val label: String, val plist: File, val folder: String?, val kind: BackgroundKind, val loaded: Boolean, val owned: File?) {
    fun problem(home: File): BackgroundProblem? = when {
        folder == null -> BackgroundProblem.NoFolder
        !File(folder).isDirectory -> BackgroundProblem.Missing
        kind == BackgroundKind.ConductorService -> BackgroundProblem.Conductor
        AgentWorkspaceRoots.refusal(File(folder), home) != null || AgentWorkspaceRoots.temporary(File(folder)) -> BackgroundProblem.Unsafe
        else -> null
    }
}

object AgentBackgroundServices {
    const val Prefix = "build.reaktor.agents."

    fun list(home: File = File(System.getProperty("user.home")), loaded: Set<String> = loadedLabels()): List<BackgroundAgent> =
        File(home, "Library/LaunchAgents").listFiles { file -> file.name.startsWith(Prefix) && file.extension == "plist" }.orEmpty()
            .sortedBy { it.name }
            .map { read(it, loaded) }

    fun flagged(agents: List<BackgroundAgent>, home: File): List<Pair<BackgroundAgent, BackgroundProblem>> =
        agents.mapNotNull { agent -> agent.problem(home)?.let { agent to it } }

    fun describe(flagged: List<Pair<BackgroundAgent, BackgroundProblem>>): String =
        BackgroundProblem.entries.mapNotNull { problem -> flagged.count { it.second == problem }.takeIf { it > 0 }?.let { "$it ${problem.phrase(it)}" } }
            .let { parts -> if (parts.size < 2) parts.joinToString("") else parts.dropLast(1).joinToString(", ") + " and " + parts.last() } + "."

    fun report(prune: Boolean, out: (String) -> Unit, home: File = File(System.getProperty("user.home")), loaded: Set<String> = loadedLabels(),
               bootout: (String) -> String? = ::bootout) {
        val agents = list(home, loaded)
        if (agents.isEmpty()) return out("No Reaktor background owners are installed for this user.")
        val flagged = flagged(agents, home)
        agents.forEach { agent ->
            val problem = agent.problem(home)?.let { "  remove: it ${it.phrase()}" }.orEmpty()
            out("${agent.label}  ${agent.kind.plain}, ${if (agent.loaded) "loaded" else "not loaded"}  ${agent.folder ?: "(no folder)"}$problem")
        }
        out("")
        out("${agents.size} background owners are installed for this user, ${agents.count { it.loaded }} of them loaded." +
            if (flagged.isEmpty()) " None needs removing." else " " + describe(flagged))
        if (flagged.isEmpty()) return
        if (!prune) return out("reaktor-agent workspace services --prune removes the ${flagged.size} marked remove.")
        out("Removing ${flagged.size}: ${flagged.joinToString { it.first.label }}")
        flagged.forEach { (agent, _) ->
            if (agent.loaded) bootout(agent.label)?.let { out("${agent.label}: $it") }
            Files.deleteIfExists(agent.plist.toPath())
            agent.owned?.takeIf { it.name == "launch-agent.plist" && it.toPath().normalize().startsWith(File(home, ".reaktor/agents").toPath().normalize()) }
                ?.let { Files.deleteIfExists(it.toPath()) }
        }
        val left = agents.size - flagged.size
        out("Removed ${flagged.size}. $left ${if (left == 1) "owner remains" else "owners remain"}.")
    }

    fun loadedLabels(): Set<String> {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return emptySet()
        return runCatching {
            val process = ProcessBuilder("/bin/launchctl", "list").redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS)
            text.lineSequence().mapNotNull { it.split('\t').getOrNull(2)?.trim() }.filter { it.startsWith(Prefix) }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun bootout(label: String): String? {
        val process = ProcessBuilder("/bin/launchctl", "bootout", "gui/${AgentBackgroundService.uid()}/$label").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); return "launchctl did not answer within 10 seconds" }
        return if (process.exitValue() == 0) null else "launchctl could not stop it: ${text.trim().take(300)}"
    }

    private fun read(file: File, loaded: Set<String>): BackgroundAgent {
        val entries = runCatching { plist(file) }.getOrDefault(emptyMap())
        val arguments = (entries["ProgramArguments"] as? List<*>).orEmpty().map { it.toString() }
        val kind = when {
            arguments.any { it.endsWith("KernelAgentServiceKt") } -> BackgroundKind.Owner
            arguments.any { it.endsWith("ConductorCliKt") } && "serve" in arguments -> BackgroundKind.ConductorService
            else -> BackgroundKind.Other
        }
        val label = entries["Label"] as? String ?: file.nameWithoutExtension
        val owned = (entries["StandardOutPath"] as? String)?.let { File(it).parentFile?.resolve("launch-agent.plist") }
        return BackgroundAgent(label, file, (entries["WorkingDirectory"] as? String)?.takeIf { it.isNotBlank() }, kind, label in loaded, owned)
    }

    private fun plist(file: File): Map<String, Any> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isExpandEntityReferences = false
        }
        val dict = factory.newDocumentBuilder().parse(file).documentElement.children().firstOrNull { it.tagName == "dict" } ?: return emptyMap()
        return entries(dict)
    }

    private fun entries(dict: Element): Map<String, Any> =
        dict.children().chunked(2).filter { it.size == 2 && it[0].tagName == "key" }.associate { (key, value) -> key.textContent.trim() to value(value) }

    private fun value(element: Element): Any = when (element.tagName) {
        "array" -> element.children().map(::value)
        "dict" -> entries(element)
        else -> element.textContent.trim()
    }

    private fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
}
