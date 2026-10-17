package dev.shibasis.reaktor.conductor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules that decide what a turn is allowed to cost.
 *
 * Each case here is a way the effort control could quietly do the wrong thing: cap the user at a
 * level both providers happen to share, downgrade a request instead of refusing it, or report an
 * assumption as if the provider had confirmed it.
 */
class CapabilityTest {
    private val claude = EffortSupport(
        supported = listOf("low", "medium", "high", "xhigh", "max").map(::NativeEffort),
        source = "claude --help",
    )

    /** `codex exec` has no effort flag and `codex models` needs a terminal, so the set is unknown. */
    private val codex = EffortSupport(supported = null, source = "-c model_reasoning_effort")

    @Test
    fun anUnknownSupportSetAcceptsTheValueInsteadOfRefusingItOnAGuess() {
        val resolution = codex.resolve(NativeEffort("xhigh"))
        assertEquals(EffortResolution.Granted(NativeEffort("xhigh")), resolution)
        assertTrue(codex.accepts(NativeEffort("anything-a-future-model-adds")))
    }

    @Test
    fun anAdvertisedSetRefusesAnUnknownLevelRatherThanDowngradingIt() {
        val resolution = claude.resolve(NativeEffort("ultra"))
        val unsupported = assertIs<EffortResolution.Unsupported>(resolution)
        assertEquals(NativeEffort("ultra"), unsupported.requested)
        assertTrue(unsupported.message.contains("xhigh"), "The error has to say what is available")
        // The point of the type: no branch of this returns a cheaper level.
        assertFailsWith<UnsupportedEffortException> { throw UnsupportedEffortException(unsupported) }
    }

    @Test
    fun askingForNothingStaysTheProvidersDefaultAndIsNotRecordedAsOurChoice() {
        assertEquals(EffortResolution.ProviderDefault, claude.resolve(null))
        assertEquals(EffortResolution.ProviderDefault, null.resolve(null))
        assertNull(EffortRecord.none.requested)
        assertNull(EffortRecord.none.resolved)
    }

    @Test
    fun aGrantedEffortIsNeverReportedAsAnObservedOne() {
        val record = EffortRecord(requested = NativeEffort("high"), resolved = NativeEffort("high"))
        assertTrue(record.unknownEffective, "Neither CLI reports an effective effort on this transport")
        assertNull(record.observed)
        assertFalse(record.copy(observed = NativeEffort("high")).unknownEffective)
    }

    @Test
    fun aCapabilityNobodyTestedIsUsableAndStillMarkedUnproven() {
        val implemented = Qualification(advertised = true, configured = true, implemented = true)
        assertTrue(implemented.usable)
        assertTrue(implemented.experimental)

        val proven = Qualification.qualified("CapabilityTest")
        assertTrue(proven.usable)
        assertFalse(proven.experimental, "A named test is what moves a capability out of experimental")

        val schemaOnly = Qualification(advertised = true)
        assertFalse(schemaOnly.usable, "Advertised is not implemented; a schema entry is not a feature")
        assertFalse(Qualification.unavailable.usable)
    }

    @Test
    fun effortRejectsBlankAndOverlongProviderTokens() {
        assertFailsWith<IllegalArgumentException> { NativeEffort(" ") }
        assertFailsWith<IllegalArgumentException> { NativeEffort("x".repeat(33)) }
    }
}
