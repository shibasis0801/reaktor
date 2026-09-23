package dev.shibasis.reaktor.tooling.mcp.door

import dev.shibasis.reaktor.tooling.SafetyClass
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/** `<workspace>/.reaktor/mcp-providers.json`: the providers a workspace adds to the door. It holds no secrets. */
@Serializable
data class DoorConfig(
    val providers: List<DoorProviderConfig> = emptyList(),
    /** Directories every child searches before PATH. It belongs in the machine's list: where a version manager put `node` is not a project's business. */
    val path: List<String> = emptyList(),
    /** Set when the workspace has a list nobody has accepted yet; its providers are left out and the door says so. */
    @kotlinx.serialization.Transient val untrusted: String? = null,
) {
    companion object {
        const val PATH = ".reaktor/mcp-providers.json"

        /**
         * The machine's providers, then the workspace's. An id named in both is the workspace's:
         * a project may retune or switch off something that is installed for every project.
         */
        fun load(workspace: File, home: File = File(System.getProperty("user.home")), trusted: (String) -> Boolean = { true }): DoorConfig {
            val machine = read(File(home, PATH))
            val listed = File(workspace, PATH)
            // A workspace's list arrives with the repository and names commands to run. Until a person
            // has accepted this exact content it is not used: cloning a project must not be enough to run its servers.
            val project = if (listed.isFile && !trusted(digest(listed))) DoorConfig(untrusted = listed.path) else read(listed)
            val overridden = project.providers.map(DoorProviderConfig::id).toSet()
            val path = machine.path + project.path
            return DoorConfig((machine.providers.filter { it.id !in overridden } + project.providers).map { provider ->
                val stdio = provider.transport as? DoorTransport.Stdio ?: return@map provider
                provider.copy(transport = stdio.copy(path = stdio.path + path))
            }, path, project.untrusted)
        }

        /** What a person accepts when they trust a workspace's list: exactly these bytes. */
        fun digest(file: File): String = sha256Hex(file.readText())

        fun workspaceList(workspace: File): File = File(workspace, PATH)

        /** One list exactly as written, for showing a person what they are about to accept. */
        fun readList(file: File): DoorConfig = read(file)

        private fun read(file: File): DoorConfig {
            if (!file.isFile) return DoorConfig()
            require(file.length() <= 256_000) { "${file.path} is too large to be a provider list" }
            return DoorJson.decodeFromString(serializer(), file.readText()).also { config ->
                config.providers.forEach { provider ->
                    require(ID.matches(provider.id)) { "${file.path}: '${provider.id}' is not a provider id (a-z, 0-9, -)" }
                    provider.prefix?.let { require(it.isEmpty() || PREFIX.matches(it)) { "${file.path}: '${it}' is not a name prefix (a-z, 0-9, _)" } }
                    (provider.transport as? DoorTransport.Http)?.let { http ->
                        val target = java.net.URI(http.url)
                        require(target.scheme == "https" || target.host in LOOPBACK) { "${file.path}: '${provider.id}' must use https unless it is on this machine" }
                    }
                }
                val repeated = config.providers.groupBy(DoorProviderConfig::id).filterValues { it.size > 1 }.keys
                require(repeated.isEmpty()) { "${file.path} names these providers more than once: $repeated" }
            }
        }

        private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1")

        private val ID = Regex("[a-z][a-z0-9-]{0,31}")
        private val PREFIX = Regex("[a-z][a-z0-9_]{0,31}")
    }
}

@Serializable
enum class DoorMode {
    /** Not listed, not callable. */
    Off,
    /** Listed from its snapshot; calling it says why it is not running. */
    SnapshotOnly,
    /** Listed from its snapshot; started by the first call. */
    OnDemand,
    /** Started with the door. */
    Running,
}

@Serializable
data class DoorProviderConfig(
    val id: String,
    val title: String? = null,
    val mode: DoorMode = DoorMode.OnDemand,
    /** Put in front of every call name. Defaults to [id]; empty keeps the provider's own names. */
    val prefix: String? = null,
    /** Absent for the providers the host builds in; an entry then only adjusts them. */
    val transport: DoorTransport? = null,
    val hide: List<String> = emptyList(),
    /** The class of every call that has no entry in [calls]. Unclassified means a person is asked. */
    val safety: SafetyClass = SafetyClass.UnknownRemoteEffect,
    /** Said to the agent when the provider is down, so it can tell a person what to start. */
    val offlineHint: String? = null,
    /** Classes for single calls, by the provider's own name for them. */
    val calls: Map<String, SafetyClass> = emptyMap(),
    /** Believe this vendor when it marks a call read-only. Only for a first-party server whose listing is pinned by its snapshot. */
    val trustReadOnlyHint: Boolean = false,
    /** Call name to the argument that carries SQL. A statement that can be shown to only read is treated as a read. */
    val sqlArguments: Map<String, String> = emptyMap(),
    /** Calls that are safe to make with no arguments. `workspace verify` makes them when a provider does not mark its own reads. */
    val probe: List<String> = emptyList(),
)

@Serializable
sealed interface DoorTransport {
    @Serializable
    @SerialName("stdio")
    data class Stdio(
        /** `{workspace}` stands for the workspace root, so no machine's path is ever committed. */
        val command: List<String>,
        val cwd: String? = null,
        val env: Map<String, String> = emptyMap(),
        /** Names copied from this process beyond the base allow-list. */
        val inheritEnv: List<String> = emptyList(),
        val startupTimeoutMillis: Long = 240_000,
        val callTimeoutMillis: Long = 120_000,
        val idleStopMillis: Long = 900_000,
        /** Variables the child needs that are secrets. Resolved when it starts; never written here. */
        val secretEnv: Map<String, DoorCredential> = emptyMap(),
        /** Directories searched before PATH, for a runtime a version manager keeps out of the way. */
        val path: List<String> = emptyList(),
        /** `{seat}` in the command becomes the calling agent's name, translated here when the provider has its own words for it. */
        val seatNames: Map<String, String> = emptyMap(),
    ) : DoorTransport

    @Serializable
    @SerialName("http")
    data class Http(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        /** Sent as `Authorization: Bearer …`. */
        val bearer: DoorCredential? = null,
        val secretHeaders: Map<String, DoorCredential> = emptyMap(),
        val callTimeoutMillis: Long = 120_000,
        /** True for a server on this machine that answers in milliseconds, so its listing is read fresh. */
        val local: Boolean = false,
    ) : DoorTransport
}

/**
 * Where a secret is, never what it is. The door asks the place at the moment of use and keeps the
 * answer in memory for a few minutes, so a provider list can be committed and a rotated token is picked up.
 */
@Serializable
sealed interface DoorCredential {
    /** Said to the agent when the secret is not there, so it can tell a person exactly what to do. */
    val whenMissing: String?

    @Serializable @SerialName("env")
    data class Env(val name: String, override val whenMissing: String? = null) : DoorCredential

    /** A tool that already holds the sign-in, such as `gh auth token` or `gcloud auth print-access-token`. */
    @Serializable @SerialName("command")
    data class Command(val command: List<String>, val cacheSeconds: Long = 300, override val whenMissing: String? = null) : DoorCredential

    /**
     * A file holding the secret on its first line. Only two places may be named: the workspace's
     * `config/` directory, which this project tracks on purpose, and `~/.reaktor/secrets`. A list
     * that could name any path could be made to post `~/.ssh/id_rsa` to a server of its choosing.
     */
    @Serializable @SerialName("file")
    data class SecretFile(val path: String, override val whenMissing: String? = null) : DoorCredential

    /** macOS keychain, generic password. Store one with `security add-generic-password -s <service> -a <account> -w`. */
    @Serializable @SerialName("keychain")
    data class Keychain(val service: String, val account: String, override val whenMissing: String? = null) : DoorCredential
}

/** What a child may see of this process. Everything else, credentials included, stays here. */
object ChildEnvironment {
    private val BASE = listOf("PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "TERM",
        "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "GRADLE_USER_HOME", "DEVELOPER_DIR")
    private val EXTRA_PATH = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin")

    fun of(transport: DoorTransport.Stdio, workspace: File, ambient: Map<String, String> = System.getenv()): Map<String, String> {
        val copied = (BASE + transport.inheritEnv).mapNotNull { name -> ambient[name]?.let { name to it } }.toMap()
        return copied + ("PATH" to searchPath(ambient, transport.path)) + transport.env.mapValues { it.value.replace(WORKSPACE, workspace.path) }
    }

    /** A desktop app hands its children a bare PATH; without the usual tool directories `maestro` or `npx` is not found. */
    fun searchPath(ambient: Map<String, String> = System.getenv(), first: List<String> = emptyList()): String =
        (first.map(::home) + ambient["PATH"].orEmpty().split(File.pathSeparator) + EXTRA_PATH).filter(String::isNotBlank).distinct().joinToString(File.pathSeparator)

    fun resolve(transport: DoorTransport.Stdio, workspace: File, seat: String?, ambient: Map<String, String> = System.getenv()): List<String> {
        require(transport.command.isNotEmpty()) { "A provider needs a command" }
        val agent = seat?.let { transport.seatNames[it] ?: it } ?: transport.seatNames[""] ?: "reaktor"
        val expanded = transport.command.map { home(it.replace(WORKSPACE, workspace.path).replace(SEAT, agent)) }
        val first = expanded.first()
        val executable = when {
            File(first).isAbsolute -> first
            first.contains(File.separatorChar) -> File(workspace, first).path
            else -> searchPath(ambient, transport.path).split(File.pathSeparator).map { File(it, first) }.firstOrNull(File::canExecute)?.path ?: first
        }
        return listOf(executable) + expanded.drop(1)
    }

    /** A bare executable name, found the way a child's would be. */
    fun command(command: List<String>, ambient: Map<String, String> = System.getenv()): List<String> {
        require(command.isNotEmpty()) { "A credential command needs an executable" }
        val first = home(command.first())
        val executable = if (first.contains(File.separatorChar)) first
            else searchPath(ambient).split(File.pathSeparator).map { File(it, first) }.firstOrNull(File::canExecute)?.path ?: first
        return listOf(executable) + command.drop(1)
    }

    private fun home(value: String): String = if (value.startsWith("~/")) System.getProperty("user.home") + value.drop(1) else value

    const val WORKSPACE = "{workspace}"
    const val SEAT = "{seat}"
}
