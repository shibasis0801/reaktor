package dev.shibasis.reaktor.conductor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reduction against real build output.
 *
 * Every fixture marked CAPTURED is output this project's own Gradle and Kotlin toolchain produced
 * on 13 September 2026. The reason to test against the real thing is the same as for the harness
 * parsers: the format belongs to someone else, and a reducer written against a tidied-up sample is
 * one that silently drops diagnostics on contact with the real build.
 */
class CheckReducerTest {
    private val command = listOf("./gradlew", ":reaktor-conductor:compileKotlinJvm")

    @Test
    fun compilerDiagnosticsBecomeAddressableFailures() {
        val result = CheckReducers.reduce(command, 1, capturedCompileFailure, revision = "abc123")
        assertEquals(CheckOutcome.Failed, result.outcome)
        assertEquals(CheckReducers.KOTLIN_PARSER, result.parser)
        val first = result.failures.first()
        // A repair turn needs somewhere to go, not a wall of text.
        assertEquals("/Users/ovd/dev/reaktor/reaktor-conductor/src/jvmMain/kotlin/dev/shibasis/reaktor/conductor/cli/CliCapabilities.kt", first.file)
        assertEquals(125, first.line)
        assertEquals(55, first.column)
        assertTrue(first.message.contains("Argument type mismatch"))
        assertEquals(3, result.failureCount)
        assertTrue(result.attributable, "A result that cannot name its revision is indistinguishable from a stale one")
    }

    @Test
    fun aWarningIsRecognisedButIsNotAFailure() {
        val result = CheckReducers.reduce(command, 0, capturedWarningOnly)
        assertEquals(CheckOutcome.Passed, result.outcome)
        assertTrue(result.failures.isEmpty(), "A build that warns and succeeds has not failed")
        assertEquals(CheckCoverage.Complete, result.coverage)
        assertNull(result.excerpt, "A passing check carries no excerpt to pay for")
    }

    @Test
    fun theReductionReportsHowMuchOfTheOutputItDidNotUnderstand() {
        // The captured fixture already carries one line no reducer claims, so assert the delta
        // the extra noise adds rather than a total that silently encodes the fixture.
        val baseline = assertIs<CheckCoverage.Partial>(CheckReducers.reduce(command, 1, capturedCompileFailure).coverage)
        val noisy = capturedCompileFailure + "\n" + List(40) { "some unrelated line $it" }.joinToString("\n")
        val result = CheckReducers.reduce(command, 1, noisy)
        val partial = assertIs<CheckCoverage.Partial>(result.coverage)
        assertEquals(baseline.unrecognisedLines + 40, partial.unrecognisedLines)
        // The claim being tested is the honesty of the reduction, not its ratio.
        assertTrue(partial.reason.isNotBlank())
    }

    @Test
    fun aFailingBuildWithNoDiagnosticIsErroredRatherThanPassed() {
        val result = CheckReducers.reduce(command, 1, "> Task :x:compileKotlinJvm FAILED\nBUILD FAILED in 2s")
        assertEquals(CheckOutcome.Errored, result.outcome, "A daemon or toolchain failure is not a passing build")
        assertNotNull(result.excerpt)
    }

    @Test
    fun testFailuresKeepTheSummaryCountEvenWhenNamesAreMissing() {
        val result = CheckReducers.reduce(listOf("./gradlew", "jvmTest"), 1, capturedTestFailure)
        assertEquals(CheckOutcome.Failed, result.outcome)
        assertEquals(CheckReducers.JUNIT_PARSER, result.parser)
        assertEquals("anErrorReplyBecomesAnExceptionCarryingTheServersCode[jvm]", result.failures.first().message)
        assertEquals(2, result.failureCount)
        // Two failed, one was named: the shortfall is stated rather than hidden.
        val partial = assertIs<CheckCoverage.Partial>(result.coverage)
        assertEquals(1, partial.unrecognisedLines)
    }

    @Test
    fun outputNoReducerUnderstandsKeepsABoundedTailAndSaysItIsUnparsed() {
        val result = CheckReducers.reduce(listOf("wrangler", "deploy"), 3, "something entirely unfamiliar")
        assertEquals(CheckOutcome.Errored, result.outcome)
        assertEquals(CheckReducers.FALLBACK_PARSER, result.parser)
        assertIs<CheckCoverage.Unparsed>(result.coverage)
        assertEquals("something entirely unfamiliar", result.excerpt)
    }

    @Test
    fun aLongLogIsCappedAndTheFullMaterialStaysRetrievable() {
        val huge = List(5000) { "line $it of a gradle log that nobody should pay to read" }.joinToString("\n")
        val result = CheckReducers.reduce(command, 1, huge, log = ArtifactRef("sha256:abc", huge.length.toLong()))
        assertTrue(result.excerpt!!.length <= 2000, "The excerpt is what enters a prompt; it has a budget")
        assertTrue(huge.length > 200_000)
        assertEquals("sha256:abc", result.log?.id, "The full log is referenced, not discarded")
    }

    @Test fun laterTaskCountsAreNotLostBehindAnEarlierPassingSummary() {
        val result = CheckReducers.reduce(command, 1, """
            > Task :a:test
            5 tests completed
            > Task :b:test
            B > first FAILED
            B > second FAILED
            8 tests completed, 3 failed
        """.trimIndent())
        assertEquals(3, result.failureCount)
        assertEquals(1, assertIs<CheckCoverage.Partial>(result.coverage).unrecognisedLines)
    }

    /** CAPTURED: `./gradlew :reaktor-conductor:compileKotlinJvm` failing on three diagnostics. */
    private val capturedCompileFailure = """
        Repository already exists at /Users/ovd/dev/reaktor/.github_modules/flatbuffers
        e: file:///Users/ovd/dev/reaktor/reaktor-conductor/src/jvmMain/kotlin/dev/shibasis/reaktor/conductor/cli/CliCapabilities.kt:125:55 Argument type mismatch: actual type is 'String?', but 'String' was expected.
        e: file:///Users/ovd/dev/reaktor/reaktor-conductor/src/jvmMain/kotlin/dev/shibasis/reaktor/conductor/cli/CliCapabilities.kt:126:49 Argument type mismatch: actual type is 'String?', but 'String' was expected.
        e: file:///Users/ovd/dev/reaktor/reaktor-conductor/src/jvmMain/kotlin/dev/shibasis/reaktor/conductor/workspace/AgentWorkspace.kt:52:29 Unresolved reference 'CliCapabilities'.
        > Task :reaktor-conductor:compileKotlinJvm FAILED
        BUILD FAILED in 3s
    """.trimIndent()

    /** CAPTURED: a clean build that still emits a Kotlin warning. */
    private val capturedWarningOnly = """
        w: file:///Users/ovd/dev/reaktor/reaktor-conductor/src/commonMain/kotlin/Foo.kt:8:14 Parameter 'x' is never used
        > Task :reaktor-conductor:compileKotlinJvm
        BUILD SUCCESSFUL in 5s
    """.trimIndent()

    /** CAPTURED: the JsonRpcStdio suite mid-development, two failing and only one named. */
    private val capturedTestFailure = """
        JsonRpcStdioTest[jvm] > anErrorReplyBecomesAnExceptionCarryingTheServersCode[jvm] FAILED
            dev.shibasis.reaktor.conductor.appserver.JsonRpcException at JsonRpcStdio.kt:78

        5 tests completed, 2 failed
        BUILD FAILED in 2s
    """.trimIndent()
}
