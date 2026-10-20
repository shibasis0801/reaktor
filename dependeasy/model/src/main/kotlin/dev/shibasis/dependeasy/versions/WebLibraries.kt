package dev.shibasis.dependeasy.versions

object WebLibraries {
    const val Hono = "4.12.23"
    const val PartyServer = "0.5.6"
    const val Postgres = "3.4.9"
    const val Miniflare = "4.20260526.0"
    const val ReactFlow = "^12.10.2"

    val Libraries = mapOf(
        "@cloudflare/workers-types" to listOf("^4.20260528.1"),
        "@google-cloud/pubsub" to listOf("^5.2.2"),
        "@types/github-slugger" to listOf("1.3.0"),
        "@types/mdast" to listOf("4.0.4"),
        "@types/node" to listOf("24.19.2"),
        "@types/react" to listOf("^19.2.2"),
        "@types/react-dom" to listOf("^19.2.0"),
        "@xyflow/react" to listOf("12.10.0", ">=12 <13"),
        "chrome-launcher" to listOf("^1.1.2"),
        "elkjs" to listOf("0.12.0"),
        "github-slugger" to listOf("1.5.0"),
        "karakum" to listOf("1.0.0-alpha.112"),
        "lighthouse" to listOf("^12.2.1"),
        "mdast-util-to-string" to listOf("4.0.0"),
        "pagefind" to listOf("1.5.2"),
        "react" to listOf(">=18", "^18.3.0 || ^19.0.0", "^18.3.1", "^19.2.6"),
        "react-dom" to listOf(">=18", "^18.3.1", "^19.2.6"),
        "rehype-raw" to listOf("7.0.0"),
        "rehype-stringify" to listOf("10.0.1"),
        "remark-directive" to listOf("3.0.1"),
        "remark-frontmatter" to listOf("5.0.0"),
        "remark-gfm" to listOf("4.0.1"),
        "remark-parse" to listOf("11.0.0"),
        "remark-rehype" to listOf("11.1.2"),
        "typescript" to listOf("6.0.3"),
        "typescript-native" to listOf("npm:typescript@7.0.2"),
        "unified" to listOf("11.0.5"),
        "unist-util-visit" to listOf("5.1.0"),
        "vite" to listOf("8.3.4"),
        "yaml" to listOf("2.9.1"),
    )
}
