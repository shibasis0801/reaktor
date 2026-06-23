package dev.shibasis.reaktor.cloud

import kotlinx.coroutines.runBlocking
import java.nio.file.Paths

/**
 * Dev verification entrypoint: dumps the Cloud inventory read from a repo's `wrangler.json`.
 * Run via `./gradlew :reaktor-cloud:verifyWrangler -PwranglerPath=/path/to/repo`.
 */
fun main(args: Array<String>) {
    val root = Paths.get(args.firstOrNull() ?: ".")
    val resources = runBlocking { CloudInventory(listOf(WranglerInventoryProvider(root))).refresh() }
    println("Cloud inventory from $root  ->  ${resources.size} resources")
    resources.groupBy { it.kind }.toSortedMap().forEach { (kind, list) ->
        println("  $kind (${list.size}):")
        list.forEach { println("    - ${it.name}   ${it.metrics}   ${it.consoleUrl ?: ""}") }
    }
}
