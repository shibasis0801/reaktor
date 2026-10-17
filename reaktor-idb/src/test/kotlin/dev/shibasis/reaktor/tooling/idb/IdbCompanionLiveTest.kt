package dev.shibasis.reaktor.tooling.idb

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Talks to a real `idb_companion` over gRPC.
 *
 * Skips when nothing is booted, because this is the one test that cannot be faked usefully: the
 * point of the client is that it speaks the companion's actual wire format, and a mock would only
 * prove it speaks its own.
 */
class IdbCompanionLiveTest {

    private fun bootedSimulator(): String? = runCatching {
        val process = ProcessBuilder("xcrun", "simctl", "list", "devices", "booted").start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        Regex("\\(([0-9A-F-]{36})\\) \\(Booted\\)").find(output)?.groupValues?.get(1)
    }.getOrNull()

    @Test
    fun describesAndDrivesABootedTarget() = runBlocking {
        if (IdbCompanion.locate() == null) {
            println("idb_companion is not installed; skipping.")
            return@runBlocking
        }
        val udid = bootedSimulator()
        if (udid == null) {
            println("No booted simulator; skipping idb companion checks.")
            return@runBlocking
        }

        IdbCompanion.start(udid).use { companion ->
            println("companion on port ${companion.port} for $udid")
            companion.client().use { client ->
                val description = client.describe()
                println("target: ${description.name} ${description.osVersion} ${description.targetType}")
                assertTrue(description.udid.isNotBlank(), "describe returned no udid")

                val apps = client.listApps()
                println("apps: ${apps.size}")
                assertTrue(apps.isNotEmpty(), "a booted simulator always has system apps")

                val png = client.screenshot()
                println("screenshot bytes: ${png.size}")
                assertTrue(png.size > 1024, "screenshot is implausibly small")
                assertTrue(
                    png.size >= 8 && png[1] == 'P'.code.toByte() && png[2] == 'N'.code.toByte(),
                    "screenshot is not a PNG",
                )

                // HID is the capability that has no simctl equivalent at all, and the reason a
                // simulator needs idb for anything resembling input.
                client.tap(100.0, 200.0)
                println("tap dispatched")

                val tree = client.accessibilityInfo(nested = true)
                println("accessibility json: ${tree.length} chars")
                assertTrue(tree.isNotBlank(), "accessibility read returned nothing")

                val line = withTimeoutOrNull(8_000) { client.log().first() }
                println("first log line: ${line?.take(90)}")
            }
        }
    }
}
