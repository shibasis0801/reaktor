package dev.shibasis.dependeasy.model.device

import dev.shibasis.dependeasy.model.BuildOption

import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.Flow
import java.io.File

class MaestroRunner(
    private val binary: String = CommandRunner.locate("maestro") ?: "maestro",
    private val workingDirectory: File = File("."),
    private val developerToolsDirectory: String? = null,
) {
    fun available(): Boolean = CommandRunner.locate("maestro") != null

    suspend fun test(
        flows: List<String>,
        deviceId: String? = null,
        reportDirectory: File,
        tags: List<String> = emptyList(),
        environment: Map<String, String> = emptyMap(),
        shards: Int = 1,
    ): MaestroRun {
        require(flows.isNotEmpty()) { "Select at least one flow" }
        reportDirectory.mkdirs()
        val report = File(reportDirectory, "report.xml")
        val argv = buildList {
            add(binary)
            deviceId?.let { addAll(listOf("--device", it)) }
            add("test")
            addAll(listOf("--format", "JUNIT", "--output", report.absolutePath))
            addAll(listOf("--debug-output", File(reportDirectory, "debug").absolutePath))
            if (shards > 1) addAll(listOf("--shard-split", shards.toString()))
            if (tags.isNotEmpty()) addAll(listOf("--include-tags", tags.joinToString(",")))
            environment.forEach { (key, value) -> addAll(listOf("-e", "$key=$value")) }
            addAll(flows)
        }
        val result = CommandRunner.run(argv, timeoutSeconds = 3_600)
        return MaestroRun(
            succeeded = result.succeeded,
            report = report.takeIf(File::exists),
            debugDirectory = File(reportDirectory, "debug").takeIf(File::exists),
            output = result.stdout + result.stderr,
            cases = report.takeIf(File::exists)?.let(::parseJUnit).orEmpty(),
        )
    }

    fun stream(flows: List<String>, deviceId: String? = null): Flow<String> = CommandRunner.stream(
        buildList {
            add(binary)
            deviceId?.let { addAll(listOf("--device", it)) }
            add("test")
            addAll(flows)
        }
    )

    suspend fun hierarchy(deviceId: String? = null): String = CommandRunner.run(
        buildList {
            add(binary)
            deviceId?.let { addAll(listOf("--device", it)) }
            add("hierarchy")
        }
    ).requireSuccess("maestro hierarchy")

    fun mcpCommand(): List<String> = listOf(binary, "mcp")

    fun physicalDevicesBlocking(platform: String) = kotlinx.coroutines.runBlocking { physicalDevices(platform) }
    fun testPhysicalBlocking(contract: dev.shibasis.dependeasy.model.DeviceFlowContract, environment: Map<String, String>) =
        kotlinx.coroutines.runBlocking { testPhysical(contract, environment) }

    suspend fun physicalDevices(platform: String): List<PhysicalDevice> {
        require(platform in setOf("android", "ios"))
        val text = CommandRunner.run(listOf(binary, "--platform", platform, "devices", "--json")).requireSuccess("physical device inventory")
        return kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArray.map { item ->
            val fields = item.jsonObject
            PhysicalDevice(fields.getValue("id").jsonPrimitive.content,
                fields.getValue("platform").jsonPrimitive.content,
                fields.getValue("kind").jsonPrimitive.content,
                fields["ready"]?.jsonPrimitive?.booleanOrNull == true)
        }
    }

    suspend fun testPhysical(contract: dev.shibasis.dependeasy.model.DeviceFlowContract, environment: Map<String, String>): MaestroRun {
        require(contract.serial != null && !contract.serial.startsWith("emulator-"))
        val directory = File(contract.reportDirectory).apply { mkdirs() }
        val argv = buildList {
            addAll(contract.processPrefix)
            addAll(listOf(binary, "--platform", contract.platform, "--device", contract.serial,
                "--no-app-install", "--auto-start-emulator=false", "--driver-start-timeout", "180"))
            contract.team?.let { addAll(listOf("--team-id", it)) }
            contract.wdaBundleId?.let { addAll(listOf("--wda-bundle-id", it)) }
            addAll(listOf("test", "--output", directory.absolutePath, "--flatten", "--artifacts", contract.artifacts))
            environment.forEach { (key, value) -> addAll(listOf("-e", "$key=$value")) }
            addAll(contract.flows)
        }
        val result = CommandRunner.run(argv, timeoutSeconds = 3600, directory = workingDirectory,
            environment = physicalRunnerEnvironment(contract.platform, developerToolsDirectory = developerToolsDirectory))
        val report = directory.walkTopDown().firstOrNull { it.name.endsWith(".xml") }
        return MaestroRun(result.succeeded, report, directory, result.stdout + result.stderr, report?.let(::parseJUnit).orEmpty())
    }

    private fun parseJUnit(report: File): List<MaestroCase> = runCatching {
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isXIncludeAware = false
        }.newDocumentBuilder().parse(report)
        val cases = document.getElementsByTagName("testcase")
        (0 until cases.length).map { index ->
            val element = cases.item(index) as org.w3c.dom.Element
            val failures = element.getElementsByTagName("failure")
            MaestroCase(
                name = element.getAttribute("name"),
                suite = element.getAttribute("classname"),
                durationSeconds = element.getAttribute("time").toDoubleOrNull() ?: 0.0,
                failure = if (failures.length > 0) failures.item(0).textContent?.trim() else null,
            )
        }
    }.getOrElse { emptyList() }
}

internal fun physicalRunnerEnvironment(platform: String, inherited: Map<String, String> = BuildOption.environmentSnapshot(), developerToolsDirectory: String? = null): Map<String, String> =
    if (platform == "ios") inherited.filterKeys { it !in setOf("CPATH", "C_INCLUDE_PATH", "CPLUS_INCLUDE_PATH", "OBJC_INCLUDE_PATH", "LIBRARY_PATH") } +
        ("LLVM_CAS_MAX_MAPPING_SIZE" to "134217728") +
        (developerToolsDirectory?.let { mapOf("PATH" to it + File.pathSeparator + inherited["PATH"].orEmpty()) } ?: emptyMap()) else inherited

data class MaestroRun(
    val succeeded: Boolean,
    val report: File?,
    val debugDirectory: File?,
    val output: String,
    val cases: List<MaestroCase>,
) {
    val failures: List<MaestroCase> get() = cases.filter { it.failure != null }
}

data class MaestroCase(
    val name: String,
    val suite: String,
    val durationSeconds: Double,
    val failure: String?,
)

data class PhysicalDevice(val id: String, val platform: String, val kind: String, val ready: Boolean)

fun selectPhysicalDevice(devices: List<PhysicalDevice>, platform: String, requested: String?): PhysicalDevice {
    val candidates = devices.filter { it.platform == platform && it.kind == "device" && !it.id.startsWith("emulator-") }
        .filter { requested == null || it.id == requested }
    require(candidates.size == 1 && candidates.single().ready) { "Connect and authorize exactly one physical $platform phone, or specify its serial" }
    return candidates.single()
}
