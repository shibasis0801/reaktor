@file:JsModule("compose-flow")
@file:JsNonModule

package dev.shibasis.composeflow.react

import react.Props

external interface ViewportChangeOptions : Props {
    var onStart: ((Viewport) -> Unit)?
    var onChange: ((Viewport) -> Unit)?
    var onEnd: ((Viewport) -> Unit)?
}

external interface SelectionChangeOptions : Props {
    var onChange: ((dynamic) -> Unit)?
}

external fun useViewport(): Viewport

external fun useOnViewportChange(options: ViewportChangeOptions): Unit

external fun useOnSelectionChange(options: SelectionChangeOptions): Unit

external fun useStore(selector: dynamic = definedExternally, equalityFn: dynamic = definedExternally): dynamic

external fun useStoreApi(): dynamic
