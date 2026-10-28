package dev.shibasis.reaktor.tooling.changes

import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.tooling.atlas.AtlasEvidence
import dev.shibasis.reaktor.tooling.atlas.AtlasFrom
import dev.shibasis.reaktor.tooling.atlas.AtlasNext
import dev.shibasis.reaktor.tooling.atlas.AtlasStrength
import dev.shibasis.reaktor.tooling.operation.Describe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable enum class ChangeRecipe { @SerialName("rename") Rename, @SerialName("edit") Edit, @SerialName("structural") Structural, @SerialName("change-type") ChangeType }

@Serializable
data class ChangeEdit(
    @Describe("A workspace-relative file; find must occur in it exactly once") val path: String? = null,
    @Describe("A handle from atlas_query or atlas_read; find is looked for only inside that declaration") val id: String? = null,
    @Describe("Exact text to replace, copied from atlas_read; it must occur exactly once where it is looked for") val find: String,
    @Describe("The text that replaces it") val replacement: String,
)

@Serializable
data class ChangePlanRequest(
    @Describe("rename renames one Kotlin or C++ declaration and its uses across modules and the builds this repository includes; edit replaces exact text in one or more files together; " +
        "structural rewrites every match of a Kotlin expression pattern; change-type moves every use of one Kotlin type to another")
    val recipe: ChangeRecipe,
    @Describe("rename: the declaration, as atlas_query names it: {name: \"Type.member\"}, {at: \"File.kt:42:9\"} or {id}. " +
        "structural: keep only the matches in which the compiler resolves a name outside the holes to this declaration, an override of it or its constructor. " +
        "change-type: the type whose uses move") val target: AtlasFrom? = null,
    @Describe("rename: the new name. change-type: the type the uses move to, as atlas_query names it, such as \"NewType\" or \"Outer.NewType\"") val to: String? = null,
    @Describe("edit: 1 to 64 replacements, written together or not at all") val edits: List<ChangeEdit> = emptyList(),
    @Describe("structural: one Kotlin expression with holes; \$X stands for any one expression and \$\$\$ARGS for any run of arguments, and a hole used twice must match the same text, " +
        "for example \$RECEIVER.answer(\$\$\$ARGS)") val pattern: String? = null,
    @Describe("structural: the text each match becomes, with the pattern's holes, for example \$RECEIVER.reply(\$\$\$ARGS)") val rewrite: String? = null,
    @Describe("structural: files or folders to search; without them every Kotlin file that names the pattern's longest name is searched") val paths: List<String> = emptyList(),
    @Describe("rename: the least evidence a renamed match may rest on. resolved, the default, renames what the compiler confirms and lists the rest as left; " +
        "syntactic also renames the matches the Atlas reads as uses where the compiler did not check them, each marked by name in the plan")
    val need: AtlasStrength? = null,
    @Describe("rename: positions from a plan's left list, as path:line:column, to rename as well, each marked by name in the plan") val include: List<String> = emptyList(),
)

@Serializable
data class ChangeLine(val line: Int, val before: String, val after: String, val evidence: AtlasStrength? = null)

@Serializable
data class ChangeFile(val path: String, val edits: Int, val lines: List<ChangeLine>, val more: Int = 0)

@Serializable
data class ChangeLeft(val at: String, val reason: String, val snippet: String)

@Serializable
data class ChangePlanned(
    val plan: String,
    val recipe: String,
    val summary: String,
    val files: List<ChangeFile>,
    val edits: Int,
    val evidence: AtlasEvidence,
    val left: List<ChangeLeft> = emptyList(),
    val checks: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val next: List<AtlasNext> = emptyList(),
    val complete: Boolean = true,
)

@Serializable
data class ChangeApplyRequest(@Describe("The plan id from change_plan") val plan: String)

@Serializable
data class ChangeApplied(
    val plan: String,
    val receipt: String,
    val files: List<String>,
    val replayed: Boolean = false,
    val checks: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val next: List<AtlasNext> = emptyList(),
)

open class ChangesApi : Service("reaktor.change") {
    open val plan by call<ChangePlanRequest, ChangePlanned>(effect = Effect.LocalEphemeral,
        summary = "Plans a source change without writing it: a plan id, every line it changes, the matches it leaves and the checks it affects. recipe rename renames one Kotlin declaration and its uses across modules and the builds this repository includes, as the compiler resolves them; a match the compiler did not confirm is left and listed, and need syntactic or include renames it as the Atlas reads it, marked by name. recipe edit replaces exact text, found once in a file or inside one declaration, in several files together. change_apply writes a plan.")
    open val apply by call<ChangeApplyRequest, ChangeApplied>(effect = Effect.SourceWrite,
        summary = "Writes one change_plan plan, every file or none, refusing it when a file changed since the plan, and returns the receipt; check_run then runs the affected checks. Repeating it returns the same receipt. A seat whose policy stops short of source writes waits for a person to approve the plan, unless its policy lets it apply its own plans, every file is in this checkout and, when the session is linked to a board task whose scope names paths or modules, inside that scope.")
}

