package dev.shibasis.reaktor.surface

enum class KeyName {
    A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    Digit0, Digit1, Digit2, Digit3, Digit4, Digit5, Digit6, Digit7, Digit8, Digit9,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    Enter, Escape, Tab, Space, Backspace, Delete,
    Up, Down, Left, Right, Home, End, PageUp, PageDown,
    LeftBracket, RightBracket, Equals, Minus, Comma, Period, Slash, ContextMenu,
}

data class KeyStroke(
    val key: KeyName,
    val meta: Boolean = false,
    val control: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val character: Char? = null,
)

enum class KeyConvention { Mac, Pc }
