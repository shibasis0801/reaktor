package dev.shibasis.reaktor.conductor.workspace

import java.io.File

object AgentWorkspaceRoots {
    private val Markers = listOf("BESTBUDS.md", "settings.gradle.kts")

    fun refusal(root: File, home: File = File(System.getProperty("user.home"))): String? {
        val folder = root.canonicalFile
        val what = when {
            folder.parentFile == null -> "the root of the file system"
            folder == home.canonicalFile -> "the home folder"
            folder in temporaryFolders() -> "the temporary folder"
            else -> return null
        }
        return "Reaktor runs no workspace owner for ${folder.path}, $what. Pass --dir with a repository folder, such as --dir ~/dev/bestbuds."
    }

    fun temporary(root: File): Boolean = root.canonicalFile.toPath().let { path -> temporaryFolders().any { path.startsWith(it.toPath()) } }

    fun enclosing(start: File, home: File = File(System.getProperty("user.home"))): File? {
        val chain = generateSequence(start.canonicalFile) { it.parentFile }.takeWhile { refusal(it, home) == null }.toList()
        return chain.firstOrNull { File(it, ".git").exists() } ?: chain.firstOrNull { folder -> Markers.any { File(folder, it).isFile } }
    }

    fun noWorkspace(start: File): String =
        "No repository encloses ${start.canonicalPath}. Run this inside a repository or pass --dir with the repository folder."

    private fun temporaryFolders(): Set<File> =
        listOfNotNull(System.getProperty("java.io.tmpdir"), System.getenv("TMPDIR"), "/tmp", "/private/tmp", "/var/tmp", "/private/var/tmp")
            .map { File(it).canonicalFile }.toSet()
}
