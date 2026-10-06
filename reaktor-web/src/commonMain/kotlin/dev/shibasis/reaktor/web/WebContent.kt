package dev.shibasis.reaktor.web

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

sealed interface WebContent {
    data class Url(val url: String) : WebContent {
        init { require(webOrigin(url) != null && '\u0000' !in url) { "Expected an HTTP or HTTPS URL" } }
    }
    data class Html(val html: String) : WebContent {
        init { require('\u0000' !in html) }
    }
    data class Bundle(val app: WebApp) : WebContent
}

data class WebAsset(val bytes: ByteArray, val mimeType: String) {
    init {
        require(bytes.size <= 8 * 1024 * 1024) { "An asset exceeds 8 MiB" }
        require(mimeType.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")))
    }
}

interface WebAssetProvider {
    val paths: Set<String>
    fun read(path: String): WebAsset?
}

class WebAssets(assets: Map<String, WebAsset>) : WebAssetProvider {
    private val assets = assets.mapValues { (_, asset) -> asset.copy(bytes = asset.bytes.copyOf()) }
    override val paths = assets.keys.toSet()
    init {
        require(paths.all { webAssetPath(it) == it })
        require(assets.values.sumOf { it.bytes.size.toLong() } <= 64 * 1024 * 1024) { "Bundle exceeds 64 MiB" }
    }
    override fun read(path: String): WebAsset? = assets[webAssetPath(path)]?.let { it.copy(bytes = it.bytes.copyOf()) }
}

operator fun WebAssetProvider.plus(other: WebAssetProvider): WebAssetProvider {
    require(paths.intersect(other.paths).isEmpty()) { "Bundle asset paths collide" }
    val first = this
    return object : WebAssetProvider {
        override val paths = first.paths + other.paths
        override fun read(path: String): WebAsset? = first.read(path) ?: other.read(path)
    }
}

data class WebApp(
    val id: String,
    val revision: String,
    val assets: WebAssetProvider,
    val entrypoint: String = "/index.html",
    val networkOrigins: Set<String> = emptySet(),
    val requiredFeatures: Set<WebFeature> = setOf(WebFeature.Bundles),
    val browserUrl: String? = null,
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9-]{0,47}")))
        require(revision.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        require(webAssetPath(entrypoint) == entrypoint && entrypoint in assets.paths)
        require(assets.paths.all { webAssetPath(it) == it })
        require(networkOrigins.all { webOrigin(it) == it && it.startsWith("https://") })
        browserUrl?.let { require(webOrigin(it) != null && '#' !in it) }
    }
    val origin: String get() = "https://$id.reaktor.invalid"
    val logicalUrl: String get() = "reaktor://$id/$revision$entrypoint"
    val contentSecurityPolicy: String get() = listOf(
        "default-src 'self'", "script-src 'self'", "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data: blob:", "font-src 'self'", "worker-src 'self' blob:",
        "connect-src 'self' ${networkOrigins.sorted().joinToString(" ")}",
        "frame-src 'none'", "object-src 'none'", "base-uri 'none'", "form-action 'none'",
    ).joinToString("; ")
}

@Serializable
enum class WebFeature { Bundles, Bridge, Evaluation, Navigation, History, Zoom, DevTools, EphemeralProfile, PersistentProfile, Downloads, BrowserStorage }

sealed interface WebProfile {
    data object Browser : WebProfile
    data object Ephemeral : WebProfile
    data class Persistent(val id: String) : WebProfile {
        init { require(id == "default" || runCatching { Uuid.parse(id) }.isSuccess) { "Persistent profile IDs are UUIDs or default" } }
    }
}

data class WebViewOptions(
    val profile: WebProfile = WebProfile.Ephemeral,
    val debug: Boolean = false,
    val policy: WebNavigationPolicy = WebNavigationPolicy(),
)

class WebUnavailable(val feature: WebFeature, message: String) : IllegalStateException(message)

data class WebNavigationPolicy(
    val allowedOrigins: Set<String> = emptySet(),
    val loopbackOrigins: Set<String> = emptySet(),
) {
    init {
        require(allowedOrigins.all { webOrigin(it) == it && it.startsWith("https://") })
        require(loopbackOrigins.all { webOrigin(it) == it && isLoopbackOrigin(it) })
    }
    fun allows(url: String): Boolean {
        val origin = webOrigin(url) ?: return url == "about:blank"
        if (origin.startsWith("http://")) return origin in loopbackOrigins
        return allowedOrigins.isEmpty() || origin in allowedOrigins
    }
}

fun webOrigin(url: String): String? {
    if (url.any { it <= ' ' || it == '\\' || it == '\u007f' }) return null
    val match = Regex("^(https?)://([^/?#]+)(?:[/?#].*)?$", RegexOption.IGNORE_CASE).matchEntire(url) ?: return null
    val scheme = match.groupValues[1].lowercase()
    val authority = match.groupValues[2].lowercase()
    if ('@' in authority || '%' in authority) return null
    val parts = Regex("^(\\[[0-9a-f:]+\\]|[a-z0-9.-]+)(?::([0-9]{1,5}))?$").matchEntire(authority) ?: return null
    val port = parts.groupValues[2]
    if (port.isNotEmpty() && port.toInt() !in 1..65535) return null
    val suffix = if (port.isEmpty() || (scheme == "https" && port == "443") || (scheme == "http" && port == "80")) "" else ":$port"
    return "$scheme://${parts.groupValues[1]}$suffix"
}

private fun isLoopbackOrigin(origin: String): Boolean =
    Regex("^http://(?:localhost|127\\.0\\.0\\.1|\\[::1\\])(?::[0-9]+)?$").matches(origin)

fun webAssetPath(address: String): String {
    val path = address.substringBefore('?').substringBefore('#')
    require(path.startsWith("/") && '\u0000' !in path && '\\' !in path)
    val decoded = buildString {
        var i = 0
        while (i < path.length) {
            if (path[i] == '%') {
                require(i + 2 < path.length)
                val value = path.substring(i + 1, i + 3).toIntOrNull(16) ?: error("Invalid asset escape")
                require(value in 0x20..0x7e && value != 0x25 && value != 0x5c && value != 0x2f) { "Encoded asset separators are forbidden" }
                append(value.toChar())
                i += 3
            } else append(path[i++])
        }
    }
    require(decoded.split('/').drop(1).all { it.isNotEmpty() && it != "." && it != ".." })
    return decoded
}

internal fun WebContent.documentOrigin(): String? = when (this) {
    is WebContent.Url -> webOrigin(url)
    is WebContent.Html -> null
    is WebContent.Bundle -> app.origin
}
