package dev.shibasis.dependeasy.model

object JavaTool {
    fun nativeAccess(enabled: Boolean): List<String> =
        if (enabled) listOf("--enable-native-access=ALL-UNNAMED") else emptyList()
    fun limitModules(modules: List<String>): List<String> = listOf("--limit-modules=${modules.joinToString(",")}")
}
