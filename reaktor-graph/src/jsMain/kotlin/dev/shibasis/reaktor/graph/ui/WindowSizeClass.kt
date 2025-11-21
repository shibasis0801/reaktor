package dev.shibasis.reaktor.graph.ui

import js.array.ReadonlyArray
import kotlinx.browser.window
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.w3c.dom.Window
import org.w3c.dom.events.Event
import react.Cleanup
import react.StateInstance
import react.StateSetter
import react.raw.useEffectRaw
import react.raw.useLayoutEffectRaw
import react.useEffect
import react.useMemo
import react.useState
import kotlin.js.unsafeCast

fun Window.widthClass() = when {
    innerWidth < 600 -> WindowWidthClass.COMPACT
    innerWidth < 840 -> WindowWidthClass.MEDIUM
    innerWidth < 1200 -> WindowWidthClass.EXPANDED
    innerWidth < 1600 -> WindowWidthClass.LARGE
    else -> WindowWidthClass.EXTRA_LARGE
}

fun Window.heightClass() = when {
    innerHeight < 480 -> WindowHeightClass.COMPACT
    innerHeight < 900 -> WindowHeightClass.MEDIUM
    else -> WindowHeightClass.EXPANDED
}

fun Window.sizeClass() = WindowSize(
    widthClass(),
    heightClass()
)

fun useLayoutEffectRaw(
    vararg dependencies: ReadonlyArray<Any?>,
    effect: () -> Cleanup?
) = useLayoutEffectRaw(effect, dependencies)


fun useEffectRaw(
    vararg dependencies: ReadonlyArray<Any?>,
    effect: () -> Cleanup?
) = useEffectRaw(effect, dependencies)

fun<T> MutableStateFlow<T>.toReactState(): StateInstance<T> {
    val (state, setState) = useState(value)

    // stateflow to react
    useEffect(Unit) {
        collect { value ->
            setState {
                state -> if (state == value) state else value
            }
        }
    }

    // react to stateflow
    val stateSetter = useMemo(Unit) {
        { valueOrTransform: dynamic ->
            if (js("typeof valueOrTransform === 'function'")) {
                val transform = valueOrTransform.unsafeCast<(T) -> T>()
                value = transform(value)
            } else {
                val newValue = valueOrTransform.unsafeCast<T>()
                value = newValue
            }
            setState(value)
        }.unsafeCast<StateSetter<T>>()
    }

    return StateInstance(state, stateSetter)
}

fun getWindowSizeFlow(): MutableStateFlow<WindowSize> {
    val state = MutableStateFlow(window.sizeClass())

    val callback = { e: Event -> state.value = window.sizeClass() }

    WindowSize.startListening(
        {
            window.addEventListener("resize", callback)
            state
        },
        {
            window.removeEventListener("resize", callback)
        }
    )

    return WindowSize.state
}

@JsExport
fun useWindowSize(): WindowSize {
    val (state, _) = getWindowSizeFlow().toReactState()
    return state
}