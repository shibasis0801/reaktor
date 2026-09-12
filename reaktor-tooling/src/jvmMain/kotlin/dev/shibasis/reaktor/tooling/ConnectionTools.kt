package dev.shibasis.reaktor.tooling

import java.io.File
import java.net.URI

object ConnectionTools {
    fun httpRead(url: String): List<String> {
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
        return listOf("curl", "--fail", "--silent", "--show-error", "--connect-timeout", "5", "--max-time", "15",
            "--max-filesize", "65536", "--header", "Accept: application/json", url)
    }

    fun d1Read(wrangler: File, config: File, database: String): List<String> {
        require(database.matches(Regex("[A-Za-z0-9_.-]+")))
        return listOf(wrangler.absolutePath, "d1", "execute", database, "--remote", "--config", config.absolutePath,
            "--command", "SELECT 1 AS connected, count(*) AS tables FROM sqlite_schema WHERE type='table';", "--json")
    }
}
