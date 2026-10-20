package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface WebBundle {
    val output: String
    @Serializable data class Vite(override val output: String = "dist") : WebBundle
    @Serializable data class Package(val script: PackageScript, override val output: String = "dist") : WebBundle
}

@Serializable data class WebGenerator(val script: PackageScript, val output: String)
@Serializable data class TypeScriptPath(val alias: String, val paths: List<String>)
@Serializable data class WebAsset(val destination: String, val source: String)

@Serializable
data class WebAppSpec(
    val typescript: List<String> = listOf("tsconfig.json"),
    val bundle: WebBundle = WebBundle.Vite(),
    val tests: PackageScript? = null,
    val generators: List<WebGenerator> = emptyList(),
    val paths: List<TypeScriptPath> = emptyList(),
    val assets: List<WebAsset> = emptyList(),
    val sources: List<String> = emptyList(),
    val dataSources: Map<String, String> = emptyMap(),
)
