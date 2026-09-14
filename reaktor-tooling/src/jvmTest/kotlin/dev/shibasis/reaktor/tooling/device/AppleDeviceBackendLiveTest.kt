package dev.shibasis.reaktor.tooling.device

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Exercises the Apple router against whatever this host is paired with.
 *
 * The assertion that matters is not that an operation succeeds — most will not, on a device whose
 * developer disk image is not mounted — but that every refusal names a cause a developer can act
 * on. That is the whole difference between this router and a list of commands that fail.
 */
class AppleDeviceBackendLiveTest {

    @Test
    fun reportsPairedDevicesAndWhyOperationsAreRefused() = runBlocking {
        val backend = AppleDeviceBackend()
        val unavailable = backend.unavailableReason()
        if (unavailable != null) {
            println("Apple backend unavailable: $unavailable")
            return@runBlocking
        }
        val devices = backend.list()
        println("paired Apple devices: ${devices.size}")
        if (devices.isEmpty()) return@runBlocking

        devices.forEach { println("  ${it.name} · ${it.runtime} · ${it.state}") }

        val session = backend.session(devices.first())
        val available = session.capabilities.filter { it.available }
        val refused = session.capabilities.filterNot { it.available }
        println("available: ${available.map { "${it.operation}(${it.provider})" }}")
        refused.take(6).forEach { println("refused ${it.operation}: ${it.reason}") }

        assertTrue(
            refused.all { !it.reason.isNullOrBlank() },
            "every refusal must carry a reason: ${refused.filter { it.reason.isNullOrBlank() }}",
        )
        assertTrue(
            session.capabilities.any { it.operation == DeviceOperation.Shell && !it.available },
            "iOS has no shell and the router must say so",
        )
    }

    @Test
    fun readsSimulators() = runBlocking {
        val backend = SimctlDeviceBackend()
        if (backend.unavailableReason() != null) return@runBlocking
        val devices = backend.list()
        println("simulators available: ${devices.size}")
        val booted = devices.filter { it.ready }
        println("booted: ${booted.map { it.name }}")
        if (devices.isEmpty()) {
            // A host can have runtimes installed and no simulators created, which is a real state
            // rather than a failure — and exactly the state this machine was in.
            println("No simulators created on this host; skipping simctl session checks.")
            return@runBlocking
        }

        val session = backend.session(devices.first()) as SimctlDeviceSession
        val forward = session.capabilities.first { it.operation == DeviceOperation.Forward }
        assertTrue(forward.available, "a simulator is always reachable on loopback")
        assertTrue(
            forward.provider.contains("loopback"),
            "the provider should record that no forwarding is involved: ${forward.provider}",
        )
    }

    @Test
    fun operatesABootedSimulator() = runBlocking {
        val backend = SimctlDeviceBackend()
        if (backend.unavailableReason() != null) return@runBlocking
        val booted = backend.list().firstOrNull { it.ready }
        if (booted == null) {
            println("No booted simulator; skipping simctl session checks.")
            return@runBlocking
        }
        val session = backend.session(booted) as SimctlDeviceSession
        println("booted simulator: ${booted.name} (${booted.runtime})")

        val uname = session.shell("uname -a").trim()
        println("spawn uname: $uname")
        assertTrue(uname.contains("Darwin"), "simctl spawn did not run inside the simulator: $uname")

        val png = session.screenshot()
        println("simulator screenshot bytes: ${png.size}")
        assertTrue(png.size > 1024, "screenshot is implausibly small")
        assertTrue(
            png.size >= 8 && png[1] == 'P'.code.toByte() && png[2] == 'N'.code.toByte(),
            "screenshot is not a PNG",
        )

        // Status-bar pinning and appearance are what make two captures comparable; both are
        // simulator-only, and both are why a simulator beats a device for visual work.
        session.pinStatusBar()
        session.setAppearance(dark = true)
        session.setAppearance(dark = false)
        session.clearStatusBar()

        val processes = session.listProcesses()
        println("simulator processes: ${processes.size}")
        assertTrue(processes.isNotEmpty(), "ps inside the simulator returned nothing")
    }
}
