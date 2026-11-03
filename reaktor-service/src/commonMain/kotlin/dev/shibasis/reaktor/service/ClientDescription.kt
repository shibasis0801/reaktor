package dev.shibasis.reaktor.service

import kotlinx.serialization.Serializable

@Serializable
data class ClientDescription(val platform: String, val app: String = "", val os: String = "", val device: String = "") {
    val kind: FleetKind? get() = when (platform) {
        "android" -> FleetKind.Android
        "ios" -> FleetKind.iOS
        "web" -> FleetKind.Web
        "jvm" -> FleetKind.Desktop
        else -> null
    }

    fun header(): String = listOf("platform" to platform, "app" to app, "os" to os, "device" to device)
        .map { (name, value) -> name to clean(value) }.filter { it.second.isNotEmpty() }.joinToString("; ") { (name, value) -> "$name=$value" }

    companion object {
        const val Header = "Reaktor-Client"
        private const val Punctuation = " ._-()+,/:"
        private val Platforms = setOf("android", "ios", "web", "jvm")
        private val Darwin = Regex("""^([^/\s]+)/(\S+) CFNetwork/\S+ Darwin/(\d+)\S*""")
        private val Browsers = listOf("Edge" to Regex("""Edg(?:e|A|iOS)?/(\d+)"""), "Opera" to Regex("""OPR/(\d+)"""), "Samsung Internet" to Regex("""SamsungBrowser/(\d+)"""),
            "Chrome" to Regex("""(?:Chrome|CriOS)/(\d+)"""), "Firefox" to Regex("""(?:Firefox|FxiOS)/(\d+)"""), "Safari" to Regex("""Version/(\d+)[\d.]* (?:Mobile/\S+ )?Safari/"""))

        fun allowed(char: Char) = char.isLetterOrDigit() || char in Punctuation

        fun clean(value: String) = value.filter(::allowed).trim().take(64)

        fun parse(header: String?, userAgent: String?): ClientDescription? {
            val declared = header.orEmpty().split(';').mapNotNull { part ->
                part.split('=', limit = 2).takeIf { it.size == 2 }?.let { (name, value) -> name.trim() to clean(value) }
            }.toMap()
            val seen = userAgent?.let(::fromUserAgent)
            val platform = declared["platform"]?.takeIf { it in Platforms } ?: seen?.platform ?: return null
            fun pick(name: String, fallback: String?) = declared[name]?.takeIf { it.isNotEmpty() } ?: fallback.orEmpty()
            return ClientDescription(platform, pick("app", seen?.app), pick("os", seen?.os), pick("device", seen?.device))
        }

        fun fromUserAgent(agent: String): ClientDescription? {
            Darwin.find(agent)?.let { found ->
                val darwin = found.groupValues[3].toInt()
                val release = if (darwin >= 25) darwin + 1 else darwin - 6
                return ClientDescription("ios", clean(found.groupValues[2]), if (release > 0) "iOS $release" else "iOS")
            }
            if (agent.startsWith("okhttp/")) return ClientDescription("android")
            if (!agent.startsWith("Mozilla/5.0")) return null
            val os = when {
                "Android" in agent -> Regex("""Android (\d+)""").find(agent)?.let { "Android ${it.groupValues[1]}" } ?: "Android"
                "iPhone" in agent || "iPad" in agent -> Regex("""OS (\d+)_(\d+)""").find(agent)?.let { "iOS ${it.groupValues[1]}.${it.groupValues[2]}" } ?: "iOS"
                "CrOS" in agent -> "ChromeOS"
                "Macintosh" in agent -> "macOS"
                "Windows" in agent -> "Windows"
                "Linux" in agent -> "Linux"
                else -> ""
            }
            val browser = Browsers.firstNotNullOfOrNull { (name, pattern) -> pattern.find(agent)?.let { "$name ${it.groupValues[1]}" } }.orEmpty()
            val device = listOf(browser, if ("iPad" in agent) "iPad" else if ("iPhone" in agent) "iPhone" else if ("Mobile" in agent) "phone" else "")
                .filter(String::isNotEmpty).joinToString(" on ")
            return ClientDescription("web", "", os, device)
        }
    }
}
