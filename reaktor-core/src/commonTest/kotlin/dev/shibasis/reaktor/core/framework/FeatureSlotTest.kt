package dev.shibasis.reaktor.core.framework

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a slot does when it is emptied.
 *
 * The slots are declared inside the class and hold strings on purpose: a top-level declaration here
 * compiles to a `FeatureSlotTestKt` class, and a fixture type compiles to one of its own, and this
 * module's test runner tries to instantiate every class it finds — which is what the exclude list
 * in `build.gradle.kts` is patching, one name at a time.
 */
class FeatureSlotTest {

    private var Feature.printer by CreateSlot<String>()
    private var Feature.scanner by CreateSlot<String>()

    @Test
    fun aSlotHoldsWhatWasPutInIt() {
        Feature.printer = "laser"

        assertEquals("laser", Feature.printer)
    }

    @Test
    fun aSlotCanBeEmptiedAgain() {
        Feature.printer = "laser"

        // The type is `T?` and this line compiles everywhere, so it has to mean something. It threw
        // from the cast in `storeDependency` instead, which left every slot permanently filled once
        // set: a test that installed an adapter handed it to every test that ran after it, and a
        // shell tearing down could not let one go.
        Feature.printer = null

        assertNull(Feature.printer)
    }

    @Test
    fun emptyingOneSlotLeavesTheOthersAlone() {
        Feature.printer = "laser"
        Feature.scanner = "flatbed"

        Feature.printer = null

        assertNull(Feature.printer)
        assertEquals("flatbed", Feature.scanner)

        Feature.scanner = null
    }
}
