package dev.shibasis.reaktor.tooling.cloud

import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class GoogleSignInRequired(message: String) : Exception(message)

class GoogleSignIn(
    home: File = File(System.getProperty("user.home")),
    private val environment: Map<String, String> = System.getenv(),
) {
    private val directory = setting("CLOUDSDK_CONFIG")?.let(::File) ?: File(home, ".config/gcloud")
    private val applicationDefault = setting("GOOGLE_APPLICATION_CREDENTIALS")?.let(::File) ?: File(directory, "application_default_credentials.json")

    fun accounts(): List<String> {
        val stored = File(directory, "legacy_credentials").listFiles { file -> File(file, "adc.json").isFile }.orEmpty().map(File::getName).sorted()
        val active = activeAccount()?.takeIf { it in stored }
        return listOfNotNull(active) + (stored - setOfNotNull(active))
    }

    fun activeAccount(): String? = setting("CLOUDSDK_CORE_ACCOUNT") ?: core()["account"]

    fun project(): String? = setting("CLOUDSDK_CORE_PROJECT") ?: core()["project"]

    fun hasApplicationDefault(): Boolean = applicationDefault.isFile

    fun signedIn(): Boolean = hasApplicationDefault() || accounts().isNotEmpty()

    fun describe(account: String?): String = account
        ?: if (hasApplicationDefault()) "application default credentials" else activeAccount() ?: "no Google sign-in"

    fun credentialFile(account: String? = null): File? = when (account) {
        null -> applicationDefault.takeIf(File::isFile) ?: accounts().firstOrNull()?.let(::stored)
        else -> account.takeIf { it in accounts() }?.let(::stored)
    }

    suspend fun token(account: String? = null, scopes: List<String> = CloudPlatformScope): String = withContext(Dispatchers.IO) {
        val file = credentialFile(account) ?: throw GoogleSignInRequired(
            if (account == null) "Google Cloud needs a sign-in: run gcloud auth login, or gcloud auth application-default login"
            else "No stored Google credentials for $account; sign it in with gcloud auth login $account")
        token(file, scopes)
    }

    private fun stored(account: String) = File(directory, "legacy_credentials/$account/adc.json")

    private fun setting(name: String): String? = environment[name]?.trim()?.takeIf(String::isNotEmpty)

    private fun core(): Map<String, String> {
        val name = setting("CLOUDSDK_ACTIVE_CONFIG_NAME")
            ?: File(directory, "active_config").takeIf(File::isFile)?.readText()?.trim()?.takeIf(String::isNotEmpty)
            ?: "default"
        val file = File(directory, "configurations/config_$name").takeIf(File::isFile) ?: return emptyMap()
        var section = ""
        return buildMap {
            file.readLines().map(String::trim).forEach { line ->
                when {
                    line.startsWith("[") && line.endsWith("]") -> section = line.removeSurrounding("[", "]").trim()
                    section == "core" && '=' in line && !line.startsWith("#") && !line.startsWith(";") ->
                        put(line.substringBefore('=').trim(), line.substringAfter('=').trim())
                }
            }
        }.filterValues(String::isNotEmpty)
    }

    companion object {
        val CloudPlatformScope: List<String> = listOf("https://www.googleapis.com/auth/cloud-platform")

        private val loaded = ConcurrentHashMap<String, GoogleCredentials>()

        fun token(file: File, scopes: List<String>): String {
            require(file.length() in 1..65_536) { "Google credential file ${file.name} is empty or exceeds limit" }
            val key = "${file.canonicalPath}|${file.lastModified()}|${scopes.joinToString(" ")}"
            val credentials = loaded.getOrPut(key) { file.inputStream().use { GoogleCredentials.fromStream(it) }.createScoped(scopes) }
            synchronized(credentials) { credentials.refreshIfExpired() }
            return credentials.accessToken?.tokenValue?.takeIf(String::isNotBlank) ?: throw GoogleSignInRequired("Google authentication failed for ${file.name}")
        }
    }
}
