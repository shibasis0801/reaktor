package dev.shibasis.reaktor.navigation.layouts

import dev.shibasis.reaktor.core.framework.Dispatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.js.JsExport

@JsExport
enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED, LARGE, EXTRA_LARGE }

@JsExport
enum class WindowHeightClass { COMPACT, MEDIUM, EXPANDED  }


/**
 * Represents window size classes based on Material Design guidelines.
 *
 * This classification helps in creating adaptive layouts by bucketing window sizes
 * into standard categories.
 *
 * ### Size Class Breakpoints
 *
 * | Size class	| Breakpoint	| Device representation	|
 * | :---	| :---	| :---	|
 * | **Compact width**	    | width < 600dp	            | 99.96% of phones in portrait	|
 * | **Medium width**	    | 600dp &le; width < 840dp	| 93.73% of tablets in portrait, most large unfolded inner displays in portrait	|
 * | **Expanded width**	    | 840dp &le; width < 1200dp	| 97.22% of tablets in landscape, most large unfolded inner displays in landscape are at least expanded width	|
 * | **Large width**	    | 1200dp &le; width < 1600dp| Large tablet displays	|
 * | **Extra-large width**	| width &ge; 1600dp	        | Desktop displays	|
 * | **Compact height**	    | height < 480dp	        | 99.78% of phones in landscape	|
 * | **Medium height**	    | 480dp &le; height < 900dp	| 96.56% of tablets in landscape, 97.59% of phones in portrait	|
 * | **Expanded height**	| height &ge; 900dp	        | 94.25% of tablets in portrait	|
 *
 * https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes
 */
@JsExport
data class WindowSize(
    val width: WindowWidthClass = WindowWidthClass.COMPACT,
    val height: WindowHeightClass = WindowHeightClass.MEDIUM
) {
    companion object: AutoCloseable {
        val state = MutableStateFlow(WindowSize())
        private var onClose: (WindowSize) -> Unit = {}

        fun startListening(
            source: Flow<WindowSize>,
            onClose: (WindowSize) -> Unit = {}
        ) {
            this.onClose = onClose
            Dispatch.Default.launch {
                source.collect { state.value = it }
            }
        }

        override fun close() {
            onClose(state.value)
        }
    }

    override fun toString(): String {
        return "(width: ${width.name}, height: ${height.name})"
    }
}