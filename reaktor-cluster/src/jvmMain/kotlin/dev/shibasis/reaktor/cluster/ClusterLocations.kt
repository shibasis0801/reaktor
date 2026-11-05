package dev.shibasis.reaktor.cluster

import java.nio.file.Files
import java.nio.file.Path

object ClusterLocations {
    /** [workspace] is the directory containing the project repositories, normally ~/dev. */
    fun state(workspace: Path = Path.of(System.getProperty("user.home"), "dev")): Path {
        val root = workspace.toAbsolutePath().normalize()
        val legacy = root.resolve(".remote-dev")
        val relocated = root.parent?.resolve("other/.remote-dev")
        if (Files.isRegularFile(legacy.resolve("config.json"))) return legacy
        if (relocated != null && Files.isRegularFile(relocated.resolve("config.json"))) return relocated
        if (Files.isDirectory(legacy)) return legacy
        return relocated?.takeIf(Files::isDirectory) ?: legacy
    }

    fun workspace(state: Path): Path {
        val parent = state.toAbsolutePath().normalize().parent
        val retained = parent.parent?.resolve("dev")
        return if (parent.fileName?.toString() == "other" && retained != null && Files.isDirectory(retained)) retained else parent
    }
}
