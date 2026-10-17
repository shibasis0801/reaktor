package dev.shibasis.dependeasy.toolchain

internal object JavaScriptChecksums {
    private val downloads = mapOf(
        "node.darwin-arm64" to "bed7eea5325e1108f32ce5228ddd6a5f0f08a499ee42aa7442aea583702f6057",
        "node.darwin-x64" to "1462cb3b3046b815cf8ea436d3da450ec1a9f11dac7e5a46b0ada5305d7e8097",
        "node.linux-arm64" to "724282c3b43aec998aa9527380465b45d229e021b58035f5f4f63095eabfe5d5",
        "node.linux-x64" to "6e1db87ef58b8819e5d5402eff1536491b18edd8eb7bee5ef7897876e88dc5ff",
        "pnpm.darwin-arm64" to "706cb8ce6a7db1990adac82bbee873368163caf1a97398853e57cb40dbf4a488",
        "pnpm.darwin-x64" to "2341f28c0dd102c0d7fa7be99eca875b9b50b52b8a96b93853370b3bf0429ea5",
        "pnpm.linux-arm64" to "45026cc79a8c5d06c604a4a624198cf96fcad7d9ab960c9a92401a0c97c93388",
        "pnpm.linux-x64" to "636abc81221a03b94f0c362bebbf0007dfce977226ad5290afed1fdaa5320aa4"
    )

    fun get(tool: String, platform: String): String = downloads.getValue("$tool.$platform")
}
