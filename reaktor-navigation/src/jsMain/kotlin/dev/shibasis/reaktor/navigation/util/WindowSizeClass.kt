package dev.shibasis.reaktor.navigation.util

import dev.shibasis.reaktor.navigation.layouts.WindowHeightClass
import dev.shibasis.reaktor.navigation.layouts.WindowWidthClass
import js.array.ReadonlyArray
import kotlinx.browser.window
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

// From Google docs on Adaptive Layouts

@JsExport
data class WindowSize(
    val width: WindowWidthClass,
    val height: WindowHeightClass
) {
    companion object: AutoCloseable {
        @JsExport.Ignore
        val state = MutableStateFlow(window.sizeClass())

        private val callback = { e: Event -> state.value = window.sizeClass() }

        override fun close() {
            window.removeEventListener("resize", callback)
        }

        init {
            window.addEventListener("resize", callback)
        }
    }

    override fun toString(): String {
        return "(width: ${width.name}, height: ${height.name})"
    }
}


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

@JsExport
fun useWindowSize(): WindowSize {
    val (state, _) = WindowSize.state.toReactState()
    return state
}