package dev.shibasis.reaktor.conductor

/**
 * Reducers for the output this repository actually produces.
 *
 * Each pattern here was written against real output captured on 13 September 2026 from this
 * project's own Gradle and Kotlin toolchain, not from documentation. Anything a reducer does not
 * recognise is counted rather than dropped, so [CheckCoverage.Partial] reports how much of the
 * output never reached the summary.
 */
object CheckReducers {
    const val KOTLIN_PARSER = "kotlin-gradle/1"
    const val JUNIT_PARSER = "gradle-test/2"
    const val FALLBACK_PARSER = "exit-status/1"

    /**
     * Kotlin compiler diagnostics as Gradle prints them:
     * `e: file:///abs/path/Foo.kt:12:34 Unresolved reference 'bar'.`
     */
    private val kotlinDiagnostic =
        Regex("""^([ew]): (?:file://)?(\S+?):(\d+):(\d+)\s+(.*)$""")

    /** Gradle's own task failure lines, which carry no file position. */
    private val gradleFailure = Regex("""^(?:FAILURE: |> Task (\S+) FAILED|> (.+))$""")

    val kotlin = CheckReducer { command, exitCode, output ->
        val lines = output.lines()
        val failures = mutableListOf<CheckFailure>()
        var recognised = 0
        var sawGradle = false
        var taskFailure: String? = null
        for (line in lines) {
            val diagnostic = kotlinDiagnostic.find(line.trim())
            if (diagnostic != null) {
                recognised++
                val (severity, file, row, column, message) = diagnostic.destructured
                // Warnings are recognised so they do not inflate the unrecognised count, but only
                // errors become failures: a build that warns and succeeds has not failed.
                if (severity == "e") failures += CheckFailure(
                    message = message.trim(),
                    file = file,
                    line = row.toIntOrNull(),
                    column = column.toIntOrNull(),
                    kind = "compile",
                )
                continue
            }
            if (line.startsWith("BUILD ") || line.startsWith("> Task ") || line.startsWith("FAILURE:")) {
                sawGradle = true
                recognised++
                if (gradleFailure.containsMatchIn(line) && line.contains("FAILED")) taskFailure = line.trim()
            }
        }
        if (failures.isEmpty() && !sawGradle) return@CheckReducer null

        // "Task X FAILED" says nothing the diagnostics above it do not, so it is only worth a
        // failure entry when nothing else explained why the build stopped.
        if (failures.isEmpty()) taskFailure?.let { failures += CheckFailure(message = it, kind = "task") }

        // A build can fail for reasons no diagnostic describes — a daemon crash, a missing
        // toolchain — so a non-zero exit with nothing parsed is Errored, not Passed.
        val outcome = when {
            exitCode == 0 && output.contains("BUILD SUCCESSFUL") -> CheckOutcome.Passed
            failures.any { it.kind == "compile" } -> CheckOutcome.Failed
            output.contains("BUILD FAILED") || (exitCode != null && exitCode != 0) -> CheckOutcome.Errored
            else -> CheckOutcome.Unknown
        }
        val unrecognised = lines.count { it.isNotBlank() } - recognised
        CheckResult(
            command = command,
            outcome = outcome,
            exitCode = exitCode,
            failures = failures.take(50),
            failureCount = failures.size,
            excerpt = if (outcome == CheckOutcome.Passed) null else output.trim().takeLast(2000).ifBlank { null },
            parser = KOTLIN_PARSER,
            coverage = if (unrecognised <= 0) CheckCoverage.Complete
            else CheckCoverage.Partial(unrecognised, "lines outside the Kotlin and Gradle diagnostic grammars"),
        )
    }

    /** Gradle's test summary: `5 tests completed, 2 failed`. */
    private val testSummary = Regex("""(\d+) tests? completed(?:, (\d+) failed)?(?:, (\d+) skipped)?""")

    val gradleTests = CheckReducer { command, exitCode, output ->
        val summaries = testSummary.findAll(output).toList()
        if (summaries.isEmpty()) return@CheckReducer null
        val namedPattern = Regex("""^\s*(\S+) > (.+) FAILED$""", RegexOption.MULTILINE)
        val named = namedPattern.findAll(output)
            .map { CheckFailure(message = it.groupValues[2], file = it.groupValues[1], kind = "test") }.toList()
        var previousEnd = 0
        var missing = 0
        var failures = 0
        summaries.forEach { summary ->
            val count = summary.groupValues[2].toIntOrNull() ?: 0
            val described = namedPattern.findAll(output.substring(previousEnd, summary.range.first)).count()
            failures += maxOf(count, described)
            missing += maxOf(0, count - described)
            previousEnd = summary.range.last + 1
        }
        failures += namedPattern.findAll(output.substring(previousEnd)).count()
        val outcome = when {
            failures > 0 -> CheckOutcome.Failed
            exitCode == 0 -> CheckOutcome.Passed
            exitCode == null -> CheckOutcome.Unknown
            else -> CheckOutcome.Errored
        }
        CheckResult(
            command = command, outcome = outcome, exitCode = exitCode,
            failures = named.take(50), failureCount = maxOf(failures, named.size),
            excerpt = if (outcome == CheckOutcome.Passed) null else summaries.joinToString("; ") { it.value }.take(2000),
            parser = JUNIT_PARSER,
            coverage = if (missing == 0) CheckCoverage.Complete
            else CheckCoverage.Partial(missing, "$missing failure(s) reported only as counts across ${summaries.size} task summaries"),
        )
    }

    /**
     * What is left when nothing recognised the format: the exit status, a bounded tail, and an
     * honest statement that the rest was not understood.
     */
    val fallback = CheckReducer { command, exitCode, output ->
        CheckResult(
            command = command,
            outcome = when (exitCode) {
                0 -> CheckOutcome.Passed
                null -> CheckOutcome.Unknown
                else -> CheckOutcome.Errored
            },
            exitCode = exitCode,
            excerpt = output.trim().takeLast(2000).ifBlank { null },
            parser = FALLBACK_PARSER,
            coverage = CheckCoverage.Unparsed("no reducer recognised this output"),
        )
    }

    /** The reducers in precedence order, ending in one that always answers. */
    val ordered: List<CheckReducer> = listOf(gradleTests, kotlin, fallback)

    /**
     * Reduces with the first reducer that recognises the output.
     *
     * [revision] is supplied by the caller rather than parsed, because only the caller knows which
     * candidate it asked to check, and a result that cannot name its revision cannot be told apart
     * from a stale one.
     */
    fun reduce(
        command: List<String>,
        exitCode: Int?,
        output: String,
        revision: String? = null,
        environment: String? = null,
        durationMillis: Long? = null,
        log: ArtifactRef? = null,
    ): CheckResult = ordered.firstNotNullOf { it.reduce(command, exitCode, output) }
        .copy(revision = revision, environment = environment, durationMillis = durationMillis, log = log)
}

private operator fun <T> List<T>.component4(): T = this[3]
private operator fun <T> List<T>.component5(): T = this[4]
