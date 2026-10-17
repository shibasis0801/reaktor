package dev.shibasis.reaktor.ui.inspect

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics

/**
 * A stable authored identity for one control, readable by any inspector that can see the semantics
 * tree — [DevTools §5.2](https://reaktor.build/docs/reaktor-devtools).
 *
 * This is deliberately graph-agnostic. It names the *element the author wrote*, and says nothing
 * about which graph node owns it: a tool joins the two, and the join reports how it was made. An
 * element id must never be overloaded with a runtime graph node id — the same control appears in
 * several list rows, previews and processes, and the runtime instances are not the definition.
 *
 * The id is a claim by the author that survives refactoring only as long as the author preserves
 * it. Prefer a namespaced, intention-revealing name over a positional one:
 *
 * ```kotlin
 * SignalButton("Continue", onClick = ::next, modifier = Modifier.reaktorElement("@onboarding/continue"))
 * ```
 *
 * An element with no id is not a failure. An inspector falls back to source and composition
 * ownership, and reports the weaker evidence rather than pretending to an exact match.
 */
val ReaktorElementIdKey = SemanticsPropertyKey<String>("ReaktorElementId")

var SemanticsPropertyReceiver.reaktorElementId: String by ReaktorElementIdKey

/** Tag this control with its stable authored identity. */
fun Modifier.reaktorElement(id: String): Modifier {
    require(id.isNotBlank()) { "An element identity cannot be blank" }
    return semantics { reaktorElementId = id }
}

/** The property name an inspector reading captured semantics looks for. */
const val REAKTOR_ELEMENT_ID_PROPERTY: String = "ReaktorElementId"
