package dev.shibasis.reaktor.blueprint.editor

import dev.shibasis.reaktor.graph.core.GraphShape
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChangeSet(
    @EncodeDefault val format: String = Format,
    @EncodeDefault val about: String = About,
    val id: String,
    val title: String,
    val revision: Int,
    val savedAt: String,
    val app: AppRef? = null,
    val workspace: String? = null,
    val summary: List<String> = emptyList(),
    val ui: List<UiChange> = emptyList(),
    val design: List<DesignChange> = emptyList(),
    val graph: GraphPart? = null,
) {
    val savedAtMillis: Long get() = runCatching { Instant.parse(savedAt).toEpochMilliseconds() }.getOrDefault(0L)

    val size: Int get() = ui.size + design.size + (graph?.changes?.size ?: 0)

    fun encode(): String = ChangeJson.encodeToString(serializer(), this)

    companion object {
        const val Format = "reaktor.changes/1"
        const val About = "Edits made in Hangar for a person or an agent to apply to the source code. " +
            "ui lists edits to elements of the running app in order; each element names the code that draws it under source, newest frame first, " +
            "its site (file:line of the call), and the components it sits in under path. scope instance means only that element changes, so the code needs " +
            "a parameter or a condition; scope site means every element drawn by that line changes, so edit the line itself. code is the Kotlin to write; " +
            "for modifier changes, from and to are the whole modifier chain before and after. " +
            "anchor names the call that draws the element as file#function/callee[ordinal], then each caller outwards, so it survives edits elsewhere in the file; " +
            "instance counts elements that share that anchor. source says where the old value comes from: literal, token, default, parameter or bound (data, with the expression in bound). " +
            "written names the file:line Hangar already changed; values holds Studio's raw edit so Hangar can reopen the change. " +
            "design lists design-system edits: token values per theme, and the default look of a component. " +
            "graph lists graph edits as operations to apply in order; layout holds the cards that moved. Every change carries a one-line summary."

        fun decode(text: String): ChangeSet = ChangeJson.decodeFromString(serializer(), text)
    }
}

@Serializable
data class AppRef(val name: String, val id: String? = null)

@Serializable
data class ElementRef(
    val id: String,
    val kind: String,
    val text: String? = null,
    val look: String? = null,
    val testTag: String? = null,
    val screen: String? = null,
    val parent: String? = null,
    val source: List<String> = emptyList(),
    val component: String? = null,
    val site: String? = null,
    val path: List<String> = emptyList(),
    val anchor: String? = null,
    val instance: Int? = null,
    val span: String? = null,
)

@Serializable
data class Component(val id: String, val kind: String, val look: String? = null, val fields: Map<String, String> = emptyMap())

@Serializable
data class UiChange(
    val op: String,
    val summary: String,
    val element: ElementRef? = null,
    val field: String? = null,
    val from: String? = null,
    val to: String? = null,
    val position: String? = null,
    val anchor: ElementRef? = null,
    val slot: String? = null,
    val component: Component? = null,
    val scope: String? = null,
    val type: String? = null,
    val code: String? = null,
    val source: String? = null,
    val bound: String? = null,
    val written: String? = null,
    val values: Map<String, String> = emptyMap(),
)

@Serializable
data class DesignChange(
    val op: String,
    val summary: String,
    val system: String,
    val theme: String? = null,
    val token: String? = null,
    val component: String? = null,
    val from: String? = null,
    val to: String? = null,
    val definedIn: String? = null,
)

@Serializable
data class GraphPart(val digest: String? = null, val changes: List<GraphChange>, val layout: Placement? = null, val definitions: Map<String, String> = emptyMap())

fun savedAt(millis: Long): String = Instant.fromEpochMilliseconds(millis).toString()

fun GraphEditor.graphPart(shape: GraphShape = this.shape, placement: Placement = this.placement): GraphPart {
    val changes = base.changesTo(shape, terms)
    val moved = placement.since(origin)
    return GraphPart(base.definitionDigest.takeIf(String::isNotBlank), changes, moved.copy(cards = moved.cards.filterKeys { shape.node(it) != null }),
        base.definitionsFor(changes))
}

fun GraphEditor.changeSet(draft: GraphDraft, app: AppRef? = null, workspace: String? = null, about: String = ChangeSet.About): ChangeSet =
    graphPart(draft.shape, draft.placement).let { part ->
        ChangeSet(about = about, id = draft.id, title = draft.title, revision = draft.revision, savedAt = savedAt(draft.savedAtMillis), app = app,
            workspace = workspace, summary = part.changes.map { it.summary }, graph = part)
    }

private val ChangeJson = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
