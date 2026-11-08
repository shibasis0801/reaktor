package dev.shibasis.reaktor.cloud.supabase

import java.io.File

class SupabaseLogin(private val workspace: File, private val environment: (String) -> String? = System::getenv) {
    fun token(): String? = environment("SUPABASE_ACCESS_TOKEN")?.takeIf { it.isNotBlank() }
        ?: TokenFiles.map { File(workspace, it) }.firstOrNull(File::isFile)
            ?.readLines()?.map(String::trim)?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    companion object {
        val TokenFiles = listOf("config/supabase.token", "config/mcp/supabase.token")
    }
}
