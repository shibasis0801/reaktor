package dev.shibasis.reaktor.portgraph

import kotlin.js.JsExport
import kotlin.uuid.Uuid

@JsExport
interface Unique {
    val id: Uuid
    val label: String
}

@JsExport
open class UniqueImpl(
    override val id: Uuid = Uuid.random(),
    override val label: String = ""
): Unique
