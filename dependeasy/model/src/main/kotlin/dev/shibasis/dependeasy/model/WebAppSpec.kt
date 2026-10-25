@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.shibasis.dependeasy.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@Serializable
sealed interface WebBundle {
    val output: String
    @Serializable data class Vite(override val output: String = "dist", val excludedAssets: List<String> = emptyList()) : WebBundle
    @Serializable data class Package(val script: PackageScript, override val output: String = "dist") : WebBundle
    @Serializable data class Node(val script: String, override val output: String = "dist", val arguments: List<String> = emptyList()) : WebBundle
}

@Serializable
sealed interface WebGenerator {
    val output: String
    @Serializable data class Node(val alias: PackageScript, val script: String, override val output: String,
        val arguments: List<String> = emptyList()) : WebGenerator
    @Serializable data class WorkerTypes(val configuration: String = "wrangler.json",
        override val output: String = "worker/types") : WebGenerator
}
@Serializable data class TypeScriptPath(val alias: String, val paths: List<String>)
@Serializable data class WebAsset(val destination: String, val source: String)

@Serializable
data class Vitest(
    val include: List<String> = listOf("**/*.{test,spec}.?(c|m)[jt]s?(x)"),
    val exclude: List<String> = listOf("**/node_modules/**", "**/.git/**"),
    val setupFiles: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val maxWorkers: Int? = null,
)

@Serializable
data class Playwright(val configuration: String, val files: List<String> = emptyList(),
    val headed: Boolean = false, val workers: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sources: List<FileInput> = emptyList())

@Serializable
data class Lighthouse(val target: String, val page: Page, val report: String,
    val profile: Profile = Profile.Mobile, val runs: Int = 3) {
    @Serializable enum class Profile { Mobile, Desktop }
    @Serializable sealed interface Page {
        @Serializable data class Remote(val url: String) : Page
        @Serializable data class Bundle(val directory: String, val server: String) : Page
    }
}

@Serializable
data class WebAppSpec(
    val typescript: List<String> = listOf("tsconfig.json"),
    val bundle: WebBundle = WebBundle.Vite(),
    val tests: Vitest? = null,
    val generators: List<WebGenerator> = emptyList(),
    val paths: List<TypeScriptPath> = emptyList(),
    val assets: List<WebAsset> = emptyList(),
    val sources: List<String> = emptyList(),
    val dataSources: Map<String, String> = emptyMap(),
)
