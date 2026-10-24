package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.model.device.*
import java.nio.file.Files
import kotlin.test.*

class PhysicalRunnerTest {
    @Test fun `physical selection requires a unique ready phone or an explicit serial`() {
        val phone = PhysicalDevice("phone", "android", "device", true)
        val simulator = PhysicalDevice("sim", "android", "simulator", true)
        assertEquals(phone, selectPhysicalDevice(listOf(phone, simulator), "android", null))
        assertFailsWith<IllegalArgumentException> { selectPhysicalDevice(listOf(phone, phone.copy(id = "other")), "android", null) }
        assertEquals(phone, selectPhysicalDevice(listOf(phone, phone.copy(id = "other")), "android", "phone"))
        assertFailsWith<IllegalArgumentException> { selectPhysicalDevice(listOf(phone.copy(ready = false)), "android", null) }
        assertFailsWith<IllegalArgumentException> { selectPhysicalDevice(listOf(simulator), "android", "sim") }
        assertFailsWith<IllegalArgumentException> { selectPhysicalDevice(listOf(phone.copy(id = "emulator-5554")), "android", null) }
    }
    @Test fun `iOS child environment excludes host include paths and bounds compiler cache mappings`() {
        val inherited = mapOf("CPATH" to "/host/include", "LIBRARY_PATH" to "/host/lib", "PATH" to "/bin", "APP_ENV" to "dev")
        val ios = physicalRunnerEnvironment("ios", inherited, "/selected/Xcode/usr/bin")
        assertFalse("CPATH" in ios)
        assertFalse("LIBRARY_PATH" in ios)
        assertEquals("/selected/Xcode/usr/bin:/bin", ios["PATH"])
        assertEquals("dev", ios["APP_ENV"])
        assertEquals("134217728", ios["LLVM_CAS_MAX_MAPPING_SIZE"])
        assertEquals(inherited, physicalRunnerEnvironment("android", inherited))
    }
    @Test fun `native runner receives physical only flags and never installs an app`() {
        val root = Files.createTempDirectory("physical-runner").toFile()
        try {
            val tool = root.resolve("runner").apply {
                writeText("#!/bin/sh\nprintf '%s\n' \"\$@\" > argv\nprintf 'passed'\n")
                setExecutable(true)
            }
            val runner = MaestroRunner(tool.absolutePath, root)
            val contract = DeviceFlowContract(platform = "ios", applicationId = "fixture.app", appOutput = "fixture:app:outputAppleXcodeFramework",
                appFile = null, flows = listOf("flow.yaml"), runner = tool.absolutePath, reportDirectory = root.resolve("reports").path,
                team = "TEAM", wdaBundleId = "fixture.wda", serial = "phone")
            assertTrue(runner.testPhysicalBlocking(contract, mapOf("APP_ENV" to "dev")).succeeded)
            val arguments = root.resolve("argv").readLines()
            assertContains(arguments, "--no-app-install")
            assertContains(arguments, "--auto-start-emulator=false")
            assertContains(arguments, "phone")
            assertFalse(arguments.any { it == "--app-file" || it == "uninstall" })
            assertContains(arguments, "APP_ENV=dev")
            val app = root.resolve("Fixture.app").absolutePath
            assertTrue(runner.testPhysicalBlocking(contract.copy(appFile = app), emptyMap()).succeeded)
            val resetArguments = root.resolve("argv").readLines()
            assertContains(resetArguments, "--no-app-install")
            assertEquals(app, resetArguments[resetArguments.indexOf("--app-file") + 1])
            assertTrue(runner.testPhysicalBlocking(contract.copy(platform = "android", appFile = app), emptyMap()).succeeded)
            assertFalse("--app-file" in root.resolve("argv").readLines())
        } finally { root.walkBottomUp().forEach { it.delete() } }
    }
}
