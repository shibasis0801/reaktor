package dev.shibasis.dependeasy.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class PackageScriptsKernelTest {
    private val iosDebug = NodeId.parse("product:iosDebug")
    private val siteBrowser = NodeId.parse("product:site:siteBrowser")

    private fun scripts(manifest: String) = Json.parseToJsonElement(manifest).jsonObject.getValue("scripts").jsonObject
        .mapValues { it.value.jsonPrimitive.content }

    @Test
    fun eachNodeKeepsOnePlainAliasAndEveryComposedAlias() {
        val spec = KernelSpec.PackageScripts(
            targets = mapOf("iosDebug" to PackageAlias(iosDebug, Effect.Build, null, emptyList()),
                "siteBrowser" to PackageAlias(siteBrowser, Effect.Verify, null, emptyList())),
            extraAliases = mapOf(
                "ios" to PackageScriptInvocation(iosDebug, "./gradlew :iosDebug"),
                "maestro:ios:build" to PackageScriptInvocation(iosDebug, "./gradlew :prepareDevice && ./gradlew :iosDebug -Pdev=true", composed = true)),
            additionalAliases = mapOf("site/package.json" to mapOf("siteBrowser" to PackageScriptInvocation(siteBrowser, "../gradlew :site:siteBrowser"))),
            output = FileSet(listOf("package.json")))

        val outputs = PackageScriptsKernel.render(spec, """{"name":"product"}""", mapOf("site/package.json" to """{"name":"site"}"""))

        assertEquals(mapOf("ios" to "./gradlew :iosDebug", "maestro:ios:build" to "./gradlew :prepareDevice && ./gradlew :iosDebug -Pdev=true"),
            scripts(outputs.getValue("package.json")))
        assertEquals(mapOf("siteBrowser" to "../gradlew :site:siteBrowser"), scripts(outputs.getValue("site/package.json")))
    }
}
