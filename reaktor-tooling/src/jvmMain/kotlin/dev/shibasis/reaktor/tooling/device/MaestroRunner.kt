package dev.shibasis.reaktor.tooling.device

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Maestro, driven from the workbench instead of from a shell script.
 *
 * The scripts in `reaktor/tools/maestro` already work; what they cannot do is pick a device from
 * the lab, stream their progress into a pane, or turn their output into typed findings. That is
 * the whole difference this makes.
 */
class MaestroRunner(
    private val binary: String = CommandRunner.locate("maestro") ?: "maestro",
    private val workingDirectory: File = File("."),
) {
    fun available(): Boolean = CommandRunner.locate("maestro") != null

    /**
     * Runs flows and returns the parsed result.
     *
     * JUnit is requested rather than Maestro's own format because it is the one every CI already
     * reads, and because the debug output directory carries the screenshots and per-command
     * timings that the report itself omits.
     */
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

    /** Streams a run line by line, for a pane that shows progress rather than a result. */
    fun stream(flows: List<String>, deviceId: String? = null): Flow<String> = CommandRunner.stream(
        buildList {
            add(binary)
            deviceId?.let { addAll(listOf("--device", it)) }
            add("test")
            addAll(flows)
        }
    )

    /** The one command that returns a view hierarchy the same way on both platforms. */
    suspend fun hierarchy(deviceId: String? = null): String = CommandRunner.run(
        buildList {
            add(binary)
            deviceId?.let { addAll(listOf("--device", it)) }
            add("hierarchy")
        }
    ).requireSuccess("maestro hierarchy")

    /**
     * Maestro's MCP server.
     *
     * 2.3.0 exposes its device and automation commands as MCP tools over stdio, which the agent
     * layer already speaks — so driving a device from a conversation costs an argv vector rather
     * than an integration.
     */
    fun mcpCommand(): List<String> = listOf(binary, "mcp")

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
