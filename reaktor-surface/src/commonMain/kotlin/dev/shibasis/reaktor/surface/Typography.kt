package dev.shibasis.reaktor.surface

enum class TypeScale { Display, Title, Title2, Title3, Title4, Body, Label, Body2, Meta, Data, Caption, Micro, Eyebrow }

enum class TypeWeight { Regular, Medium, Strong, Bold }

enum class TypeFace { Text, Code, Chrome }

data class TextRole(val scale: TypeScale, val weight: TypeWeight = TypeWeight.Regular, val face: TypeFace = TypeFace.Text) {
    val regular: TextRole get() = copy(weight = TypeWeight.Regular)
    val medium: TextRole get() = copy(weight = TypeWeight.Medium)
    val strong: TextRole get() = copy(weight = TypeWeight.Strong)
    val bold: TextRole get() = copy(weight = TypeWeight.Bold)
    val code: TextRole get() = copy(face = TypeFace.Code)
    val chrome: TextRole get() = copy(face = TypeFace.Chrome)
}

object Type {
    val Display = TextRole(TypeScale.Display)
    val Title = TextRole(TypeScale.Title)
    val Title2 = TextRole(TypeScale.Title2)
    val Title3 = TextRole(TypeScale.Title3)
    val Title4 = TextRole(TypeScale.Title4)
    val Body = TextRole(TypeScale.Body)
    val Label = TextRole(TypeScale.Label)
    val Body2 = TextRole(TypeScale.Body2)
    val Meta = TextRole(TypeScale.Meta)
    val Data = TextRole(TypeScale.Data)
    val Caption = TextRole(TypeScale.Caption)
    val Micro = TextRole(TypeScale.Micro)
    val Eyebrow = TextRole(TypeScale.Eyebrow, TypeWeight.Strong)
}

interface InkRole

enum class Ink : InkRole { Strong, Text, Muted, Unknown, Accent, Source, Busy, Ok, Warn, Danger, Inverse, OnAccent }

val TextRole.token: String
    get() = listOfNotNull(scale.name, weight.takeIf { it != TypeWeight.Regular }?.name?.lowercase(), face.takeIf { it != TypeFace.Text }?.name?.lowercase()).joinToString(".")

fun textRoleOf(token: String): TextRole? {
    val parts = token.split('.')
    val scale = TypeScale.entries.firstOrNull { it.name == parts.first() } ?: return null
    val rest = parts.drop(1)
    return TextRole(
        scale,
        TypeWeight.entries.firstOrNull { weight -> rest.any { it.equals(weight.name, ignoreCase = true) } } ?: TypeWeight.Regular,
        TypeFace.entries.firstOrNull { face -> rest.any { it.equals(face.name, ignoreCase = true) } } ?: TypeFace.Text,
    )
}

val InkRole.token: String get() = (this as? Ink)?.name ?: toString()

fun inkOf(token: String): InkRole? = Ink.entries.firstOrNull { it.name == token }
