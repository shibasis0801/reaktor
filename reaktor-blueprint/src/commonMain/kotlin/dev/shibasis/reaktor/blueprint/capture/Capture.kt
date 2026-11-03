package dev.shibasis.reaktor.blueprint.capture

import dev.shibasis.reaktor.blueprint.editor.Note as LegacyNote
import dev.shibasis.reaktor.blueprint.editor.Spot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CaptureAuthor(val name: String, val profile: String? = null, val profileRevision: Long? = null)

@Serializable
sealed interface Capture {
    val id: String
    val anchor: CaptureAnchor
    val author: CaptureAuthor?
    val style: CaptureStyle

    @Serializable
    @SerialName("note")
    data class Note(
        override val id: String,
        val text: String,
        val width: Double,
        val height: Double,
        override val anchor: CaptureAnchor,
        override val author: CaptureAuthor? = null,
        val tint: Long = 0,
        override val style: CaptureStyle = CaptureStyle(),
    ) : Capture

    @Serializable
    @SerialName("text")
    data class Text(
        override val id: String,
        val text: String,
        override val anchor: CaptureAnchor,
        override val author: CaptureAuthor? = null,
        override val style: CaptureStyle = CaptureStyle(),
    ) : Capture

    @Serializable
    @SerialName("box")
    data class Box(
        override val id: String,
        val width: Double,
        val height: Double,
        override val anchor: CaptureAnchor,
        override val author: CaptureAuthor? = null,
        val angle: Double = 0.0,
        override val style: CaptureStyle = CaptureStyle(),
    ) : Capture

    @Serializable
    @SerialName("diamond")
    data class Diamond(
        override val id: String,
        val width: Double,
        val height: Double,
        override val anchor: CaptureAnchor,
        override val author: CaptureAuthor? = null,
        val angle: Double = 0.0,
        override val style: CaptureStyle = CaptureStyle(),
    ) : Capture

    @Serializable
    @SerialName("ellipse")
    data class Ellipse(
        override val id: String,
        val width: Double,
        val height: Double,
        override val anchor: CaptureAnchor,
        override val author: CaptureAuthor? = null,
        val angle: Double = 0.0,
        override val style: CaptureStyle = CaptureStyle(),
    ) : Capture

    @Serializable
    @SerialName("freehand")
    data class Freehand(
        override val id: String,
        val points: List<Spot>,
        override val anchor: CaptureAnchor,
        override val author: CaptureAuthor? = null,
        override val style: CaptureStyle = CaptureStyle(),
    ) : Capture

    @Serializable
    @SerialName("arrow")
    data class Arrow(
        override val id: String,
        override val anchor: CaptureAnchor,
        val end: CaptureAnchor,
        val bends: List<Spot> = emptyList(),
        override val author: CaptureAuthor? = null,
        override val style: CaptureStyle = CaptureStyle(),
    ) : Capture

    @Serializable
    @SerialName("line")
    data class Line(
        override val id: String,
        override val anchor: CaptureAnchor,
        val end: CaptureAnchor,
        val bends: List<Spot> = emptyList(),
        override val author: CaptureAuthor? = null,
        override val style: CaptureStyle = CaptureStyle(),
    ) : Capture

    @Serializable
    @SerialName("frame")
    data class Frame(
        override val id: String,
        val title: String,
        val width: Double,
        val height: Double,
        override val anchor: CaptureAnchor,
        override val author: CaptureAuthor? = null,
        val parent: String? = null,
        val tint: Long = 0,
        override val style: CaptureStyle = CaptureStyle(),
    ) : Capture

    @Serializable
    @SerialName("image")
    data class Image(
        override val id: String,
        val asset: String,
        val width: Double,
        val height: Double,
        override val anchor: CaptureAnchor,
        override val author: CaptureAuthor? = null,
        val alt: String = "",
        override val style: CaptureStyle = CaptureStyle(),
        val crop: CaptureCrop? = null,
    ) : Capture

    @Serializable
    @SerialName("comment")
    data class Comment(
        override val id: String,
        val text: String,
        override val anchor: CaptureAnchor,
        override val author: CaptureAuthor? = null,
        val replyTo: String? = null,
        override val style: CaptureStyle = CaptureStyle(),
    ) : Capture
}

fun LegacyNote.toCapture(): Capture.Frame = Capture.Frame(
    id = id,
    title = text,
    width = width,
    height = height,
    anchor = CaptureAnchor.World(Spot(x, y)),
    parent = parent,
    tint = tint,
)
