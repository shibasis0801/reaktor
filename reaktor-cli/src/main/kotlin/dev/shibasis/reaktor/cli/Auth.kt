package dev.shibasis.reaktor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.Base64

private fun tokenFile() = File(System.getProperty("user.home"), ".reaktor/token")

/** `reaktor auth <login|token|whoami>` — a minimal local token store + JWT inspection. */
class Auth : CliktCommand("auth") {
    override fun run() {}
}

class AuthLogin : CliktCommand("login") {
    private val env by requireObject<ReaktorEnv>()
    private val token by option("--token", help = "the reaktor/access token (JWT) to store").default("")
    override fun run() {
        val value = token.ifEmpty { System.getenv("REAKTOR_TOKEN").orEmpty() }
        if (value.isBlank()) {
            throw UsageError(
                "Provide a token: `reaktor auth login --token <jwt>`, or set REAKTOR_TOKEN. " +
                    "(Interactive browser/PKCE login is a later phase.)"
            )
        }
        val f = tokenFile()
        f.parentFile?.mkdirs()
        f.writeText(value.trim())
        env.terminal.println(green("✓") + " token stored at " + dim(f.path))
    }
}

class AuthToken : CliktCommand("token") {
    private val env by requireObject<ReaktorEnv>()
    override fun run() {
        val f = tokenFile()
        if (!f.exists()) throw UsageError("No token. Run `reaktor auth login --token <jwt>`.")
        env.terminal.println(f.readText().trim())
    }
}

class AuthWhoami : CliktCommand("whoami") {
    private val env by requireObject<ReaktorEnv>()
    override fun run() {
        val f = tokenFile()
        if (!f.exists()) throw UsageError("No token. Run `reaktor auth login --token <jwt>`.")
        val parts = f.readText().trim().split(".")
        if (parts.size < 2) throw CliktError("Stored value is not a JWT.")
        val padded = parts[1].padEnd((parts[1].length + 3) / 4 * 4, '=')
        val payload = runCatching { String(Base64.getUrlDecoder().decode(padded)) }.getOrNull()
            ?: throw CliktError("Could not base64-decode the JWT payload.")
        val claims = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: throw CliktError("Could not parse the JWT payload as JSON.")
        env.terminal.println(bold("token claims"))
        claims.forEach { (k, v) -> env.terminal.println("  $k = " + dim(v.toString())) }
    }
}
