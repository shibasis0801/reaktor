package dev.shibasis.reaktor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextStyles.dim
import java.io.File

/**
 * `reaktor install` — make `reaktor` available from any terminal. Copies the application into
 * `~/.reaktor/libexec`, writes a launcher at `~/.reaktor/bin/reaktor`, and puts that directory
 * on PATH via the shell rc. Mirrors rustup / deno install / volta.
 */
class Install : CliktCommand() {
    private val env by requireObject<ReaktorEnv>()
    private val noModifyPath by option("--no-modify-path", help = "don't edit your shell rc").flag()

    override fun run() {
        val t = env.terminal
        val dest = File(System.getProperty("user.home"), ".reaktor")
        val libexec = File(dest, "libexec")
        val bin = File(dest, "bin")

        val sourceApp = appHome()
        installApp(sourceApp, libexec)
        val realLauncher = File(libexec, "bin/reaktor")
        realLauncher.setExecutable(true)

        bin.mkdirs()
        val launcher = File(bin, "reaktor")
        launcher.writeText("#!/bin/sh\nexec \"" + realLauncher.absolutePath + "\" \"\$@\"\n")
        launcher.setExecutable(true)
        t.println(green("✓") + " installed reaktor → " + dim(launcher.absolutePath))

        // Record the source checkout so `reaktor self update` can rebuild from it.
        sourceApp.parentFile?.parentFile?.parentFile?.takeIf { File(it, "build.gradle.kts").exists() }?.let {
            File(dest, "source").writeText(it.absolutePath)
        }

        if (!noModifyPath) ensureOnPath(bin)
        t.println("Restart your shell (or `source ~/.zshrc`), then run: " + green("reaktor doctor"))
    }

    private fun installApp(sourceApp: File, libexec: File) {
        val dest = libexec.parentFile
        val staging = File(dest, "libexec.tmp")
        val previous = File(dest, "libexec.previous")

        if (staging.exists()) staging.deleteRecursively()
        sourceApp.copyRecursively(staging, overwrite = true)

        if (previous.exists()) previous.deleteRecursively()
        if (libexec.exists() && !libexec.renameTo(previous)) {
            libexec.deleteRecursively()
        }
        if (!staging.renameTo(libexec)) {
            staging.copyRecursively(libexec, overwrite = true)
            staging.deleteRecursively()
        }
        if (previous.exists()) previous.deleteRecursively()
    }

    /** Locate the running app's home from the classpath (installDist puts the jars under home/lib). */
    private fun appHome(): File {
        val first = System.getProperty("java.class.path").split(File.pathSeparator).first()
        val jar = File(first).absoluteFile
        return jar.parentFile?.parentFile ?: jar.parentFile ?: jar
    }

    private fun ensureOnPath(bin: File) {
        val marker = "# reaktor-cli"
        val rc = File(System.getProperty("user.home"), ".zshrc")
        val text = if (rc.exists()) rc.readText() else ""
        if (marker in text) {
            env.terminal.println(dim("• PATH already configured in ~/.zshrc"))
            return
        }
        rc.appendText("\nexport PATH=\"" + bin.absolutePath + ":\$PATH\"  " + marker + "\n")
        env.terminal.println(green("✓") + " added " + dim(bin.absolutePath) + " to PATH in ~/.zshrc")
    }
}
