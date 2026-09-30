package dev.shibasis.reaktor.conductor.workspace

import java.io.File

// Finder and launchd do not inherit the interactive shell's Node/CLI installation paths.
internal fun agentServicePath(inherited: String? = System.getenv("PATH"), home: File = File(System.getProperty("user.home"))): String = buildList {
    addAll(inherited.orEmpty().split(File.pathSeparator).filter { it.isNotBlank() })
    addAll(listOf(".local/bin", ".volta/bin", ".asdf/shims", ".local/share/mise/shims", ".bun/bin").map { File(home, it).path })
    val versions = File(home, ".nvm/versions/node").listFiles().orEmpty().filter { it.isDirectory }.sortedByDescending { it.lastModified() }.take(50)
    addAll(versions.map { File(it, "bin") }.filter { File(it, "codex").canExecute() || File(it, "claude").canExecute() }.map { it.path })
    addAll(listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin"))
}.distinct().joinToString(File.pathSeparator)
