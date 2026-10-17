package dev.shibasis.reaktor.tooling.device

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Exercises the adb backend against whatever is actually attached.
 *
 * Skips rather than fails when nothing is plugged in, because a unit-test run on a build machine
 * should not depend on hardware — but when a device *is* attached, this is the only thing that
 * proves the client talks to it, which parsing stdout never did.
 */
class AdbDeviceBackendLiveTest {

    @Test
    fun readsAnAttachedDevice() = runBlocking {
        AdbDeviceBackend().use { backend ->
            val devices = backend.list()
            if (devices.isEmpty()) {
                println("No Android device attached; skipping live adb checks.")
                return@runBlocking
            }
            val device = devices.first { it.ready }
            println("device: ${device.name} (${device.id}) state=${device.state} runtime=${device.runtime}")

            backend.session(device).use { session ->
                val release = session.shell("getprop ro.build.version.release").trim()
                println("android version: $release")
                assertTrue(release.isNotBlank(), "getprop returned nothing")

                val apps = session.listApps()
                println("third-party packages: ${apps.size}")

                val processes = session.listProcesses()
                println("processes: ${processes.size}")
                assertTrue(processes.isNotEmpty(), "ps returned no processes")

                val png = session.screenshot()
                println("screenshot bytes: ${png.size}")
                assertTrue(png.size > 1024, "screenshot is implausibly small")
                assertTrue(
                    png.size >= 8 && png[1] == 'P'.code.toByte() && png[2] == 'N'.code.toByte(),
                    "screenshot is not a PNG",
                )

                val port = session.forward(0, RemoteSocket.LocalAbstract("reaktor-devtools"))
                println("forwarded host port: $port")
                assertTrue(port > 0, "forward did not report a port")
                session.removeForward(port)

                val capabilities = session.capabilities.filter { it.available }.map { it.operation }
                println("capabilities: $capabilities")
            }
        }
    }
}
