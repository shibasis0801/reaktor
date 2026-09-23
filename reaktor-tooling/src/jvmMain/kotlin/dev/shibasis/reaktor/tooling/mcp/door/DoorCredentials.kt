package dev.shibasis.reaktor.tooling.mcp.door

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A credential that is not there. The message is written for the person who has to supply it. */
class CredentialMissing(message: String) : IllegalStateException(message)

/**
 * Fetches a secret from the place a [DoorCredential] names.
 *
 * The value goes from that place into one request header or one child's environment. It is never
 * logged, never returned to a caller and never written down; it stays in memory a few minutes so
 * a burst of calls does not start a process for each.
 */
class DoorCredentials(
    private val ambient: Map<String, String> = System.getenv(),
    private val workspace: java.io.File? = null,
    private val home: java.io.File = java.io.File(System.getProperty("user.home")),
) {
    private class Held(val value: String, val until: Long)

    private val held = ConcurrentHashMap<DoorCredential, Held>()

    fun resolve(credential: DoorCredential): String {
        held[credential]?.takeIf { it.until > System.currentTimeMillis() }?.let { return it.value }
        val (value, seconds) = when (credential) {
            is DoorCredential.Env -> (ambient[credential.name]?.takeIf(String::isNotBlank)
                ?: missing(credential, "set the environment variable ${credential.name}")) to 60L
            is DoorCredential.Command -> run(ChildEnvironment.command(credential.command, ambient), credential) to credential.cacheSeconds
            is DoorCredential.SecretFile -> fromFile(credential) to 60L
            is DoorCredential.Keychain -> run(listOf("/usr/bin/security", "find-generic-password", "-s", credential.service, "-a", credential.account, "-w"), credential,
                "store it once with: security add-generic-password -s ${credential.service} -a ${credential.account} -w") to 300L
        }
        held[credential] = Held(value, System.currentTimeMillis() + seconds * 1000)
        return value
    }

    private fun fromFile(credential: DoorCredential.SecretFile): String {
        val named = credential.path.replace(ChildEnvironment.WORKSPACE, workspace?.path.orEmpty()).let { if (it.startsWith("~/")) home.path + it.drop(1) else it }
        val file = java.io.File(named).let { if (it.isAbsolute) it else java.io.File(workspace ?: home, it.path) }
        val allowed = listOfNotNull(workspace?.let { java.io.File(it, "config") }, java.io.File(home, ".reaktor/secrets"))
        val shown = allowed.joinToString(" or ") { it.path }
        val outside = CredentialMissing("${file.path} is outside $shown, the only places a provider list may read a secret from")
        if (allowed.none { file.toPath().normalize().startsWith(it.toPath().normalize()) }) throw outside
        if (!file.isFile) missing(credential, "put it on the first line of ${file.path}")
        // Again through links, so a name inside an allowed folder cannot lead out of it.
        val real = file.toPath().toRealPath()
        if (allowed.none { it.isDirectory && real.startsWith(it.toPath().toRealPath()) }) throw outside
        if (file.length() > 16_384) missing(credential, "${file.path} is too large to be a token")
        return file.useLines { lines -> lines.map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } }
            ?: missing(credential, "${file.path} is empty; put the token on its first line")
    }

    /** After a 401 the cached value is the likely culprit: a token expired or was rotated. */
    fun forget(credential: DoorCredential) { held.remove(credential) }

    private fun run(command: List<String>, credential: DoorCredential, hint: String? = null): String {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(false).apply {
                environment().clear()
                listOf("PATH", "HOME", "USER", "LOGNAME", "LANG", "TMPDIR", "CLOUDSDK_CONFIG", "XDG_CONFIG_HOME", "GH_CONFIG_DIR").forEach { name -> ambient[name]?.let { environment()[name] = it } }
                environment()["PATH"] = ChildEnvironment.searchPath(ambient)
            }.start()
        } catch (spawn: Exception) { missing(credential, hint ?: "install ${command.first()} and sign in with it") }
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        if (!finished || process.exitValue() != 0 || output.isBlank() || output.length > 16_384) missing(credential, hint ?: "sign in with ${command.first()} first")
        return output.lineSequence().first().trim()
    }

    private fun missing(credential: DoorCredential, fallback: String): Nothing =
        throw CredentialMissing(credential.whenMissing ?: "sign-in required: $fallback")
}
