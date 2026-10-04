package dev.shibasis.reaktor.surface.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.KeyStroke

expect fun platformKeyConvention(): KeyConvention

internal expect val ContextMenuKey: Key

fun KeyEvent.stroke(): KeyStroke? {
    val name = KeyNames[key] ?: return null
    return KeyStroke(name, isMetaPressed, isCtrlPressed, isAltPressed, isShiftPressed, utf16CodePoint.printable())
}

internal fun Modifier.onEscape(onEscape: () -> Unit): Modifier = onKeyEvent { event ->
    val escape = event.type == KeyEventType.KeyDown && event.stroke() == EscapeStroke
    if (escape) onEscape()
    escape
}

private val EscapeStroke = KeyStroke(KeyName.Escape)

private fun Int.printable(): Char? =
    takeIf { it in 1..0xFFFE }?.toChar()?.takeUnless { it.isISOControl() || it.isSurrogate() }

private val KeyNames: Map<Key, KeyName> = mapOf(
    Key.A to KeyName.A, Key.B to KeyName.B, Key.C to KeyName.C, Key.D to KeyName.D, Key.E to KeyName.E,
    Key.F to KeyName.F, Key.G to KeyName.G, Key.H to KeyName.H, Key.I to KeyName.I, Key.J to KeyName.J,
    Key.K to KeyName.K, Key.L to KeyName.L, Key.M to KeyName.M, Key.N to KeyName.N, Key.O to KeyName.O,
    Key.P to KeyName.P, Key.Q to KeyName.Q, Key.R to KeyName.R, Key.S to KeyName.S, Key.T to KeyName.T,
    Key.U to KeyName.U, Key.V to KeyName.V, Key.W to KeyName.W, Key.X to KeyName.X, Key.Y to KeyName.Y,
    Key.Z to KeyName.Z,
    Key.Zero to KeyName.Digit0, Key.One to KeyName.Digit1, Key.Two to KeyName.Digit2, Key.Three to KeyName.Digit3,
    Key.Four to KeyName.Digit4, Key.Five to KeyName.Digit5, Key.Six to KeyName.Digit6, Key.Seven to KeyName.Digit7,
    Key.Eight to KeyName.Digit8, Key.Nine to KeyName.Digit9,
    Key.NumPad0 to KeyName.Digit0, Key.NumPad1 to KeyName.Digit1, Key.NumPad2 to KeyName.Digit2, Key.NumPad3 to KeyName.Digit3,
    Key.NumPad4 to KeyName.Digit4, Key.NumPad5 to KeyName.Digit5, Key.NumPad6 to KeyName.Digit6, Key.NumPad7 to KeyName.Digit7,
    Key.NumPad8 to KeyName.Digit8, Key.NumPad9 to KeyName.Digit9,
    Key.F1 to KeyName.F1, Key.F2 to KeyName.F2, Key.F3 to KeyName.F3, Key.F4 to KeyName.F4, Key.F5 to KeyName.F5,
    Key.F6 to KeyName.F6, Key.F7 to KeyName.F7, Key.F8 to KeyName.F8, Key.F9 to KeyName.F9, Key.F10 to KeyName.F10,
    Key.F11 to KeyName.F11, Key.F12 to KeyName.F12,
    Key.Enter to KeyName.Enter, Key.NumPadEnter to KeyName.Enter, Key.Escape to KeyName.Escape, Key.Tab to KeyName.Tab,
    Key.Spacebar to KeyName.Space, Key.Backspace to KeyName.Backspace, Key.Delete to KeyName.Delete,
    Key.DirectionUp to KeyName.Up, Key.DirectionDown to KeyName.Down, Key.DirectionLeft to KeyName.Left,
    Key.DirectionRight to KeyName.Right, Key.MoveHome to KeyName.Home, Key.MoveEnd to KeyName.End,
    Key.PageUp to KeyName.PageUp, Key.PageDown to KeyName.PageDown,
    Key.LeftBracket to KeyName.LeftBracket, Key.RightBracket to KeyName.RightBracket,
    Key.Equals to KeyName.Equals, Key.Minus to KeyName.Minus, Key.NumPadSubtract to KeyName.Minus,
    Key.Comma to KeyName.Comma, Key.Period to KeyName.Period, Key.Slash to KeyName.Slash,
    ContextMenuKey to KeyName.ContextMenu,
)

internal val ComposeKeys: Map<KeyName, Key> = KeyNames.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.first() }
