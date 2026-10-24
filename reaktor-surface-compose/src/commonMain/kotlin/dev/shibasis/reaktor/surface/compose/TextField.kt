package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import dev.shibasis.reaktor.surface.PressKernel
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot

data class FieldProperties(val enabled: Boolean, val error: Boolean, val empty: Boolean)

class FieldSlots(
    val editor: @Composable () -> Unit,
    val placeholder: (@Composable () -> Unit)?,
    val leading: (@Composable () -> Unit)?,
    val trailing: (@Composable () -> Unit)?,
)

interface FieldAppearance : ComposeAppearance<FieldProperties, PressState, FieldSlots> {
    @Composable
    fun textStyle(properties: FieldProperties, theme: ThemeSnapshot): TextStyle

    @Composable
    fun cursor(properties: FieldProperties, theme: ThemeSnapshot): Brush
}

@Composable
fun TextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: Boolean = false,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.SingleLine,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    appearance: FieldAppearance = LocalAppearances.current.field,
    placeholder: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    label: String? = null,
) = Field("TextField", state, modifier, enabled, error, label, appearance, placeholder, leading, trailing) { field, style, source, cursor, decorator ->
    val observer = LocalSurfaceEventObserver.current
    val element = currentCompositeKeyHash.toUInt().toString(16)
    BasicTextField(
        state = state,
        modifier = field,
        enabled = enabled,
        textStyle = style,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = if (observer == null) onKeyboardAction else KeyboardActionHandler { default ->
            observer.onEvent(element, "submit") { onKeyboardAction?.onKeyboardAction(default) ?: default() }
        },
        inputTransformation = observer?.let { observe -> object : InputTransformation {
            override fun TextFieldBuffer.transformInput() { observe.onEvent(element, "edit") { } }
        } },
        lineLimits = lineLimits,
        interactionSource = source,
        cursorBrush = cursor,
        decorator = decorator,
    )
}

@Composable
fun SecureTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    appearance: FieldAppearance = LocalAppearances.current.field,
) = Field("SecureTextField", state, modifier, enabled, false, null, appearance, null, null, null) { field, style, source, cursor, decorator ->
    val observer = LocalSurfaceEventObserver.current
    val element = currentCompositeKeyHash.toUInt().toString(16)
    BasicSecureTextField(
        state = state,
        inputTransformation = observer?.let { observe -> object : InputTransformation {
            override fun TextFieldBuffer.transformInput() { observe.onEvent(element, "edit") { } }
        } },
        enabled = enabled,
        modifier = field,
        textStyle = style,
        interactionSource = source,
        cursorBrush = cursor,
        textObfuscationMode = TextObfuscationMode.Hidden,
        decorator = decorator,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Field(
    kind: String,
    state: TextFieldState,
    modifier: Modifier,
    enabled: Boolean,
    error: Boolean,
    label: String?,
    appearance: FieldAppearance,
    placeholder: (@Composable () -> Unit)?,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    editor: @Composable (Modifier, TextStyle, MutableInteractionSource, Brush, TextFieldDecorator) -> Unit,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FieldBody(state, modifier, enabled, error, label, appearance, placeholder, leading, trailing, editor)
    else studio.Element(SurfaceCall(kind, modifier, listOfNotNull(SurfaceField.text("label", label), SurfaceField.flag("enabled", enabled), SurfaceField.flag("error", error),
        SurfaceField.text("value", state.text.toString()).copy(editable = false).takeIf { kind == "TextField" }), appearance, Appearance.Field, listOf("placeholder", "leading", "trailing"))) { edits ->
        FieldBody(state, edits.modifier, edits.flag("enabled", enabled), edits.flag("error", error), edits.value("label")?.ifEmpty { null } ?: label, edits.appearance(appearance),
            edits.optional("placeholder", placeholder), edits.optional("leading", leading), edits.optional("trailing", trailing), editor)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FieldBody(
    state: TextFieldState,
    modifier: Modifier,
    enabled: Boolean,
    error: Boolean,
    label: String?,
    appearance: FieldAppearance,
    placeholder: (@Composable () -> Unit)?,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    editor: @Composable (Modifier, TextStyle, MutableInteractionSource, Brush, TextFieldDecorator) -> Unit,
) {
    val properties = FieldProperties(enabled, error, state.text.isEmpty())
    val press = rememberMachine(PressKernel, PressProperties(enabled)) {}
    val source = rememberInteractions(press)
    val theme = LocalThemeSnapshot.current
    val shown = press.state.forced()
    val feedback = rememberFeedback(shown.pressed, shown.focused)
    val reveal = remember { BringIntoViewRequester() }
    LaunchedEffect(press.state.focused, LocalWindowInfo.current.containerSize) {
        if (press.state.focused) {
            withFrameNanos { }
            reveal.bringIntoView()
        }
    }
    editor(
        (if (label == null) modifier else modifier.semantics { contentDescription = label }).bringIntoViewRequester(reveal),
        appearance.textStyle(properties, theme),
        source,
        appearance.cursor(properties, theme),
        TextFieldDecorator { inner -> appearance.Content(properties, shown, theme, feedback, FieldSlots(inner, placeholder, leading, trailing)) },
    )
}

val BareField: FieldAppearance = object : FieldAppearance {
    @Composable
    override fun textStyle(properties: FieldProperties, theme: ThemeSnapshot) = TextStyle.Default

    @Composable
    override fun cursor(properties: FieldProperties, theme: ThemeSnapshot): Brush = SolidColor(Color.Black)

    @Composable
    override fun Content(properties: FieldProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: FieldSlots) {
        Row(
            Modifier
                .defaultMinSize(minHeight = 48.dp)
                .border(if (state.focused) 2.dp else 1.dp, if (properties.error) Color.Red else Color.Gray)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slots.leading?.invoke()
            Box(Modifier.weight(1f)) {
                if (properties.empty) slots.placeholder?.invoke()
                slots.editor()
            }
            slots.trailing?.invoke()
        }
    }
}
