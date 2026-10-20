package dev.shibasis.reaktor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The JVM has no API for this, so the desktop's own setting is read where there is a command that
 * reports it: macOS's Reduce motion, and GNOME's animations switch. Elsewhere — Windows, other
 * Linux desktops — the answer is false. Read again every few seconds, which is how a change made
 * in System Settings reaches a window that is already open.
 */
@Composable
actual fun rememberReduceMotion(): Boolean {
    val reduced by produceState(initialValue = false) {
        while (true) {
            value = withContext(Dispatchers.IO) { desktopReducesMotion() }
            delay(RECHECK_MILLIS)
        }
    }
    return reduced
}

private const val RECHECK_MILLIS = 5_000L

private fun desktopReducesMotion(): Boolean {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        "mac" in os -> read("defaults", "read", "com.apple.universalaccess", "reduceMotion") == "1"
        "linux" in os -> read("gsettings", "get", "org.gnome.desktop.interface", "enable-animations") == "false"
        else -> false
    }
}

/** The command's output, or null when it is absent, fails, or takes longer than a moment. */
private fun read(vararg command: String): String? = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    if (!process.waitFor(1, TimeUnit.SECONDS)) {
        process.destroy()
        return null
    }
    process.inputStream.bufferedReader().readText().trim().takeIf { process.exitValue() == 0 }
}.getOrNull()
