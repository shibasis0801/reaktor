package dev.shibasis.reaktor.cloud

import java.awt.Desktop
import java.net.URI

/**
 * Opens a URL in the user's browser — the "Open ↗" action on any resource (provider console,
 * Grafana dashboard, Pulumi stack). Generalises the IDE-open opener already used for graph nodes.
 */
object ReaktorBrowser {
    fun open(url: String): Boolean = runCatching {
        val os = System.getProperty("os.name").lowercase()
        when {
            Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) ->
                Desktop.getDesktop().browse(URI(url))
            os.contains("mac") -> ProcessBuilder("open", url).start()
            os.contains("nix") || os.contains("nux") -> ProcessBuilder("xdg-open", url).start()
            os.contains("win") -> ProcessBuilder("cmd", "/c", "start", url).start()
            else -> return false
        }
        true
    }.getOrDefault(false)
}
