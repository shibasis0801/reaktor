package dev.shibasis.dependeasy.model

object Git {
    fun initialize(directory: String) = listOf("init", directory)
    fun origin(directory: String, repository: String) = listOf("-C", directory, "remote", "add", "origin", repository)
    fun fetch(directory: String, revision: String) = listOf("-C", directory, "fetch", "--depth=1", "origin", revision)
    fun checkout(directory: String) = listOf("-C", directory, "checkout", "--detach", "FETCH_HEAD")
    fun revision(directory: String) = listOf("-C", directory, "rev-parse", "HEAD")
    fun changes(directory: String) = listOf("-C", directory, "status", "--porcelain", "--untracked-files=no")
}
